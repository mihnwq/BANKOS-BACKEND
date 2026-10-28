package com.bankos.service;

import com.bankos.config.BankosProperties;
import com.bankos.dto.BatchEvent;
import com.bankos.dto.BatchResponse;
import com.bankos.model.BankingCase;
import com.bankos.model.Batch;
import com.bankos.repository.AgentExecutionRepository;
import com.bankos.repository.AuditEventRepository;
import com.bankos.repository.BatchRepository;
import com.bankos.repository.CaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Validates and stores uploaded batches and exposes batch status and the event timeline. */
@Service
public class BatchService {

    private static final int MAX_INVOICES = 50;

    private final BatchRepository batchRepository;
    private final CaseRepository caseRepository;
    private final AgentExecutionRepository agentExecutionRepository;
    private final AuditEventRepository auditEventRepository;
    private final CsvImportService csvImportService;
    private final AuditService auditService;
    private final Path uploadRoot;

    public BatchService(BatchRepository batchRepository, CaseRepository caseRepository,
                        AgentExecutionRepository agentExecutionRepository, AuditEventRepository auditEventRepository,
                        CsvImportService csvImportService, AuditService auditService, BankosProperties properties) {
        this.batchRepository = batchRepository;
        this.caseRepository = caseRepository;
        this.agentExecutionRepository = agentExecutionRepository;
        this.auditEventRepository = auditEventRepository;
        this.csvImportService = csvImportService;
        this.auditService = auditService;
        this.uploadRoot = Path.of(properties.storage().uploadDir());
    }

    public synchronized Batch createBatch(MultipartFile payments, MultipartFile settlements, List<MultipartFile> invoices) {
        List<String> problems = new ArrayList<>();
        requireCsv(payments, "payments", problems);
        requireCsv(settlements, "settlements", problems);
        List<MultipartFile> pdfs = invoices == null ? List.of()
                : invoices.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (pdfs.size() > MAX_INVOICES) {
            problems.add("at most " + MAX_INVOICES + " invoice PDFs per batch");
        }
        Set<String> names = new HashSet<>();
        for (MultipartFile pdf : pdfs) {
            String name = safeName(pdf.getOriginalFilename(), "invoice.pdf");
            if (!name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                problems.add(name + ": invoices must be .pdf files");
            } else if (!names.add(name.toLowerCase(Locale.ROOT))) {
                problems.add(name + ": duplicate invoice file name");
            } else if (!isPdf(pdf)) {
                problems.add(name + ": not a PDF document");
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.badRequest("Upload rejected", problems);
        }

        byte[] paymentBytes = bytes(payments);
        byte[] settlementBytes = bytes(settlements);
        String paymentsName = safeName(payments.getOriginalFilename(), "payments.csv");
        String settlementsName = safeName(settlements.getOriginalFilename(), "settlements.csv");
        csvImportService.validatePaymentsHeader(paymentBytes, paymentsName);
        csvImportService.validateSettlementsHeader(settlementBytes, settlementsName);
        if (paymentsName.equalsIgnoreCase(settlementsName)) {
            settlementsName = "settlements-" + settlementsName;
        }

        Batch batch = new Batch(nextBatchId());
        Path dir = batchDir(batch.getId());
        try {
            Files.createDirectories(dir.resolve("invoices"));
            Files.write(dir.resolve(paymentsName), paymentBytes);
            Files.write(dir.resolve(settlementsName), settlementBytes);
            for (MultipartFile pdf : pdfs) {
                pdf.transferTo(dir.resolve("invoices").resolve(safeName(pdf.getOriginalFilename(), "invoice.pdf")).toAbsolutePath());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store uploaded files", e);
        }
        batch.setPaymentsFile(paymentsName);
        batch.setSettlementsFile(settlementsName);
        batch.setPaymentsSha256(sha256(paymentBytes));
        batch.setInvoiceFileCount(pdfs.size());
        batchRepository.save(batch);
        auditService.record(batch.getId(), null, "BATCH_UPLOADED", AuditService.SYSTEM_ACTOR,
                paymentsName + ", " + settlementsName + ", " + pdfs.size() + " invoice PDF(s)");
        return batch;
    }

    public Batch getBatch(String id) {
        return batchRepository.findById(id).orElseThrow(() -> ApiException.notFound("Batch", id));
    }

    public List<BatchResponse> listBatches() {
        return batchRepository.findAllByOrderByCreatedAtDesc().stream().map(this::toResponse).toList();
    }

    public BatchResponse toResponse(Batch batch) {
        List<BankingCase> cases = caseRepository.findByBatchIdOrderByIdAsc(batch.getId());
        Map<String, Long> byStatus = cases.stream()
                .collect(Collectors.groupingBy(c -> c.getStatus().name(), TreeMap::new, Collectors.counting()));
        return BatchResponse.from(batch, cases.size(), byStatus);
    }

    /** Agent stage/tool executions and audit events for a batch, in chronological order. */
    public List<BatchEvent> events(String batchId) {
        getBatch(batchId);
        return Stream.concat(
                        agentExecutionRepository.findByBatchIdOrderByIdAsc(batchId).stream().map(BatchEvent::from),
                        auditEventRepository.findByBatchIdOrderByIdAsc(batchId).stream().map(BatchEvent::from))
                .sorted(Comparator.comparing(BatchEvent::timestamp))
                .toList();
    }

    public Path batchDir(String batchId) {
        return uploadRoot.resolve(batchId);
    }

    public Path paymentsPath(Batch batch) {
        return batchDir(batch.getId()).resolve(batch.getPaymentsFile());
    }

    public Path settlementsPath(Batch batch) {
        return batchDir(batch.getId()).resolve(batch.getSettlementsFile());
    }

    public List<Path> invoicePaths(Batch batch) {
        Path dir = batchDir(batch.getId()).resolve("invoices");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String nextBatchId() {
        long n = batchRepository.count() + 1;
        String id;
        do {
            id = String.format("B%03d", n++);
        } while (batchRepository.existsById(id));
        return id;
    }

    private static void requireCsv(MultipartFile file, String part, List<String> problems) {
        if (file == null || file.isEmpty()) {
            problems.add(part + ": file is required and must not be empty");
        } else if (!safeName(file.getOriginalFilename(), part + ".csv").toLowerCase(Locale.ROOT).endsWith(".csv")) {
            problems.add(part + ": must be a .csv file");
        }
    }

    private static boolean isPdf(MultipartFile file) {
        try (var in = file.getInputStream()) {
            byte[] header = in.readNBytes(5);
            return new String(header, StandardCharsets.US_ASCII).equals("%PDF-");
        } catch (IOException e) {
            return false;
        }
    }

    /** Keeps only the file name part and a conservative character set (no path traversal). */
    static String safeName(String original, String fallback) {
        if (original == null || original.isBlank()) {
            return fallback;
        }
        String name = Path.of(original.replace('\\', '/')).getFileName().toString();
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return name.isBlank() || name.startsWith(".") ? fallback : name;
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
