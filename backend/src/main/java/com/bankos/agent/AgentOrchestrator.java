package com.bankos.agent;

import com.bankos.config.BankosProperties;
import com.bankos.dto.Discrepancy;
import com.bankos.dto.InvestigationResult;
import com.bankos.dto.PolicyDecision;
import com.bankos.model.BankingCase;
import com.bankos.model.Batch;
import com.bankos.model.BatchStatus;
import com.bankos.model.CaseStatus;
import com.bankos.model.CaseType;
import com.bankos.model.Evidence;
import com.bankos.model.ExecutionState;
import com.bankos.model.Invoice;
import com.bankos.model.InvestigationStatus;
import com.bankos.model.Payment;
import com.bankos.model.PolicyVersion;
import com.bankos.model.ReviewStatus;
import com.bankos.model.Settlement;
import com.bankos.repository.BatchRepository;
import com.bankos.repository.CaseRepository;
import com.bankos.repository.EvidenceRepository;
import com.bankos.repository.InvoiceRepository;
import com.bankos.repository.PaymentRepository;
import com.bankos.repository.PolicyRepository;
import com.bankos.repository.SettlementRepository;
import com.bankos.service.ApiException;
import com.bankos.service.AuditService;
import com.bankos.service.BatchService;
import com.bankos.service.CsvImportService;
import com.bankos.service.PdfService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Executes the batch pipeline and records every state transition:
 * <pre>
 * UPLOADED -> PARSING -> RECONCILING -> INVESTIGATING -> APPLYING_POLICY -> READY_FOR_REVIEW
 * </pre>
 * Any stage may end in FAILED; cases already persisted are kept. Each stage result is persisted
 * before the next stage starts, and no JPA transaction is held open across model latency.
 * Processing the same batch twice never creates duplicate cases (case IDs are content-derived).
 */
@Service
public class AgentOrchestrator {

    public static final String AGENT = "AgentOrchestrator";

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    /** Investigation order when the per-batch budget is smaller than the number of cases. */
    private static final List<CaseType> PRIORITY = List.of(
            CaseType.DUPLICATE_SETTLEMENT, CaseType.DUPLICATE_PAYMENT, CaseType.AMBIGUOUS_MATCH,
            CaseType.INVOICE_MISMATCH, CaseType.AMOUNT_MISMATCH, CaseType.CURRENCY_MISMATCH,
            CaseType.MISSING_SETTLEMENT, CaseType.UNEXPECTED_SETTLEMENT, CaseType.REFERENCE_MISMATCH,
            CaseType.MISSING_INVOICE, CaseType.EXPORT_DUPLICATE);

    private final BatchRepository batchRepository;
    private final PaymentRepository paymentRepository;
    private final SettlementRepository settlementRepository;
    private final InvoiceRepository invoiceRepository;
    private final CaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final PolicyRepository policyRepository;
    private final BatchService batchService;
    private final CsvImportService csvImportService;
    private final PdfService pdfService;
    private final DocumentExtractionAgent documentExtractionAgent;
    private final ReconciliationAgent reconciliationAgent;
    private final InvestigationAgent investigationAgent;
    private final PolicyAgent policyAgent;
    private final AuditService auditService;
    private final ExecutorService processingExecutor;
    private final BankosProperties properties;
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public AgentOrchestrator(BatchRepository batchRepository, PaymentRepository paymentRepository,
                             SettlementRepository settlementRepository, InvoiceRepository invoiceRepository,
                             CaseRepository caseRepository, EvidenceRepository evidenceRepository,
                             PolicyRepository policyRepository, BatchService batchService,
                             CsvImportService csvImportService, PdfService pdfService,
                             DocumentExtractionAgent documentExtractionAgent, ReconciliationAgent reconciliationAgent,
                             InvestigationAgent investigationAgent, PolicyAgent policyAgent, AuditService auditService,
                             ExecutorService processingExecutor, BankosProperties properties) {
        this.batchRepository = batchRepository;
        this.paymentRepository = paymentRepository;
        this.settlementRepository = settlementRepository;
        this.invoiceRepository = invoiceRepository;
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.policyRepository = policyRepository;
        this.batchService = batchService;
        this.csvImportService = csvImportService;
        this.pdfService = pdfService;
        this.documentExtractionAgent = documentExtractionAgent;
        this.reconciliationAgent = reconciliationAgent;
        this.investigationAgent = investigationAgent;
        this.policyAgent = policyAgent;
        this.auditService = auditService;
        this.processingExecutor = processingExecutor;
        this.properties = properties;
    }

    /**
     * Validates the request and starts the pipeline, in the background when
     * {@code bankos.processing.async} is true. Returns the batch in its current state.
     */
    public Batch start(String batchId, Integer requestedPolicyVersion) {
        Batch batch = batchService.getBatch(batchId);
        PolicyVersion policy = resolvePolicy(batch, requestedPolicyVersion);
        if (!running.add(batchId)) {
            throw ApiException.conflict("Batch " + batchId + " is already being processed");
        }
        batch.setPolicyVersionId(policy.getId());
        batch.setStatus(BatchStatus.PARSING);
        batch.setErrorMessage(null);
        batchRepository.save(batch);
        if (properties.processing().async()) {
            processingExecutor.submit(() -> runGuarded(batchId, policy));
        } else {
            runGuarded(batchId, policy);
        }
        return batchService.getBatch(batchId);
    }

    private PolicyVersion resolvePolicy(Batch batch, Integer requested) {
        if (batch.getPolicyVersionId() != null) {
            if (requested != null && !requested.equals(batch.getPolicyVersionId())) {
                throw ApiException.conflict("Batch " + batch.getId() + " is bound to policy version "
                        + batch.getPolicyVersionId() + "; use POST /api/policies/replay to compare versions");
            }
            return policy(batch.getPolicyVersionId());
        }
        if (requested != null) {
            return policy(requested);
        }
        return policyRepository.findAllByOrderByIdAsc().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No policy version loaded"));
    }

    private PolicyVersion policy(Integer id) {
        return policyRepository.findById(id).orElseThrow(() -> ApiException.notFound("Policy version", String.valueOf(id)));
    }

    private void runGuarded(String batchId, PolicyVersion policy) {
        try {
            process(batchId, policy);
        } finally {
            running.remove(batchId);
        }
    }

    void process(String batchId, PolicyVersion policy) {
        Instant started = Instant.now();
        auditService.record(batchId, null, "PROCESSING_STARTED", AuditService.SYSTEM_ACTOR,
                "policy version " + policy.getId() + ", AI mode " + properties.ai().mode());
        Batch batch = batchService.getBatch(batchId);
        batch.setProcessingStartedAt(started);
        batchRepository.save(batch);
        BatchStatus stage = BatchStatus.PARSING;
        try {
            stage(batchId, BatchStatus.PARSING, () -> parse(batchId));
            stage = BatchStatus.RECONCILING;
            stage(batchId, BatchStatus.RECONCILING, () -> reconcile(batchId));
            stage = BatchStatus.INVESTIGATING;
            stage(batchId, BatchStatus.INVESTIGATING, () -> investigateOpenCases(batchId));
            stage = BatchStatus.APPLYING_POLICY;
            stage(batchId, BatchStatus.APPLYING_POLICY, () -> applyPolicy(batchId, policy));

            batch = batchService.getBatch(batchId);
            batch.setStatus(BatchStatus.READY_FOR_REVIEW);
            batch.setProcessedAt(Instant.now());
            batch.setDurationMs(Duration.between(started, batch.getProcessedAt()).toMillis());
            batchRepository.save(batch);
            auditService.record(batchId, null, "STATE_READY_FOR_REVIEW", AuditService.SYSTEM_ACTOR,
                    "processed in " + batch.getDurationMs() + " ms");
        } catch (RuntimeException e) {
            log.error("Batch {} failed during {}", batchId, stage, e);
            batch = batchService.getBatch(batchId);
            batch.setStatus(BatchStatus.FAILED);
            batch.setErrorMessage(stage + ": " + Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()));
            batch.setProcessedAt(Instant.now());
            batch.setDurationMs(Duration.between(started, batch.getProcessedAt()).toMillis());
            batchRepository.save(batch);
            auditService.record(batchId, null, "STATE_FAILED", AuditService.SYSTEM_ACTOR, batch.getErrorMessage());
        }
    }

    private void stage(String batchId, BatchStatus status, StageWork work) {
        Batch batch = batchService.getBatch(batchId);
        BatchStatus previous = batch.getStatus();
        batch.setStatus(status);
        batchRepository.save(batch);
        auditService.record(batchId, null, "STATE_" + status, AuditService.SYSTEM_ACTOR, previous + " -> " + status);
        Instant started = Instant.now();
        try {
            String detail = work.run();
            auditService.execution(batchId, null, AGENT, status.name(), ExecutionState.SUCCEEDED, started, detail, null);
        } catch (RuntimeException e) {
            auditService.execution(batchId, null, AGENT, status.name(), ExecutionState.FAILED, started, null, e.getMessage());
            throw e;
        }
    }

    @FunctionalInterface
    private interface StageWork {
        String run();
    }

    // ------------------------------------------------------------------ PARSING

    private String parse(String batchId) {
        Batch batch = batchService.getBatch(batchId);
        if (paymentRepository.countByBatchId(batchId) > 0) {
            return "already parsed; source records are immutable";
        }
        try {
            var payments = csvImportService.parsePayments(batchService.paymentsPath(batch), batchId, batch.getPaymentsFile());
            var settlements = csvImportService.parseSettlements(batchService.settlementsPath(batch), batchId,
                    batch.getSettlementsFile());
            paymentRepository.saveAll(payments.rows());
            settlementRepository.saveAll(settlements.rows());
            List<String> quarantined = new ArrayList<>(payments.quarantined());
            quarantined.addAll(settlements.quarantined());
            quarantined.forEach(q -> auditService.record(batchId, null, "ROW_QUARANTINED", AuditService.SYSTEM_ACTOR, q));

            List<Invoice> invoices = new ArrayList<>();
            for (Path pdf : batchService.invoicePaths(batch)) {
                String fileName = pdf.getFileName().toString();
                List<PdfService.PageText> pages;
                try {
                    pages = pdfService.extractPages(pdf);
                } catch (IOException e) {
                    Invoice failed = new Invoice(batchId, fileName);
                    failed.setVerificationNotes("PDF could not be opened: " + e.getMessage());
                    invoices.add(failed);
                    continue;
                }
                invoices.add(documentExtractionAgent.extractInvoice(batchId, fileName, pages));
            }
            invoiceRepository.saveAll(invoices);

            batch = batchService.getBatch(batchId);
            batch.setPaymentCount(payments.rows().size());
            batch.setSettlementCount(settlements.rows().size());
            batch.setInvoiceCount(invoices.size());
            batch.setQuarantinedRowCount(quarantined.size());
            batchRepository.save(batch);
            return payments.rows().size() + " payments, " + settlements.rows().size() + " settlements, "
                    + invoices.size() + " invoices, " + quarantined.size() + " quarantined rows";
        } catch (IOException e) {
            throw new IllegalStateException("Could not read uploaded files: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ RECONCILING

    private String reconcile(String batchId) {
        List<Payment> payments = paymentRepository.findByBatchIdOrderByRowNumberAsc(batchId);
        List<Settlement> settlements = settlementRepository.findByBatchIdOrderByRowNumberAsc(batchId);
        List<Invoice> invoices = invoiceRepository.findByBatchIdOrderBySourceFileAsc(batchId);

        List<Discrepancy> discrepancies = new ArrayList<>(reconciliationAgent.reconcile(payments, settlements));
        discrepancies.addAll(reconciliationAgent.reconcileInvoices(payments, invoices));

        int created = 0;
        int existing = 0;
        Set<String> paymentsWithFindings = new HashSet<>();
        for (Discrepancy d : discrepancies) {
            if (d.paymentId() != null) {
                paymentsWithFindings.add(d.paymentId());
            }
            if (d.type() == CaseType.AMBIGUOUS_MATCH) {
                d.sources().stream().filter(s -> "candidate".equals(s.field())).forEach(s -> paymentsWithFindings.add(s.recordId()));
            }
            String caseId = caseId(batchId, d);
            if (caseRepository.existsById(caseId)) {
                existing++;
                continue;
            }
            caseRepository.save(toCase(caseId, batchId, d));
            evidenceRepository.saveAll(toEvidence(caseId, d));
            auditService.record(batchId, caseId, "CASE_CREATED", ReconciliationAgent.class.getSimpleName(), d.description());
            created++;
        }

        long cleanPayments = payments.stream().map(Payment::getPaymentId).distinct()
                .filter(id -> !paymentsWithFindings.contains(id)).count();
        Batch batch = batchService.getBatch(batchId);
        batch.setMatchedPaymentCount((int) cleanPayments);
        batchRepository.save(batch);
        return discrepancies.size() + " discrepancies; " + created + " new cases, " + existing + " already existed; "
                + batch.getMatchedPaymentCount() + " payments matched cleanly";
    }

    public static String caseId(String batchId, Discrepancy d) {
        return "CASE-" + batchId + "-" + d.subjectId() + "-" + d.type().code();
    }

    private static BankingCase toCase(String caseId, String batchId, Discrepancy d) {
        BankingCase c = new BankingCase(caseId, batchId, d.type());
        c.setSubjectId(d.subjectId());
        c.setPaymentId(d.paymentId());
        c.setSettlementIds(d.settlementIds());
        c.setReference(d.reference());
        c.setInvoiceNumber(d.invoiceNumber());
        c.setExpectedAmount(d.expectedAmount());
        c.setActualAmount(d.actualAmount());
        c.setDifference(d.difference());
        c.setCurrency(d.currency());
        c.setDescription(d.description());
        c.setInvoiceRequired(d.invoiceRequired());
        c.setInvoiceAvailable(d.invoiceAvailable());
        return c;
    }

    private static List<Evidence> toEvidence(String caseId, Discrepancy d) {
        List<Evidence> evidence = new ArrayList<>();
        String prefix = "EV-" + caseId.substring("CASE-".length()) + "-";
        int n = 1;
        for (Discrepancy.SourceRef s : d.sources()) {
            evidence.add(new Evidence(prefix + n++, caseId, s.source(), s.rowNumber(), s.pageNumber(), s.field(),
                    s.recordId(), s.value()));
        }
        return evidence;
    }

    // ------------------------------------------------------------------ INVESTIGATING

    private String investigateOpenCases(String batchId) {
        List<BankingCase> open = caseRepository.findByBatchIdOrderByIdAsc(batchId).stream()
                .filter(c -> c.getInvestigationStatus() == InvestigationStatus.NOT_RUN)
                .sorted(Comparator.comparingInt((BankingCase c) -> PRIORITY.indexOf(c.getType()))
                        .thenComparing(BankingCase::getId))
                .toList();
        int budget = Math.max(0, properties.processing().maxInvestigationsPerBatch());
        int done = 0;
        int failed = 0;
        for (BankingCase c : open) {
            if (done >= budget) {
                break;
            }
            InvestigationStatus status = runInvestigation(c);
            if (status == InvestigationStatus.FAILED || status == InvestigationStatus.REJECTED) {
                failed++;
            }
            done++;
        }
        return done + " cases investigated (" + failed + " failed/rejected and escalated), "
                + Math.max(0, open.size() - done) + " left for on-demand investigation";
    }

    /** Runs (or reruns) the investigation for one case and persists the outcome. No transaction is held. */
    private InvestigationStatus runInvestigation(BankingCase bankingCase) {
        InvestigationAgent.InvestigationOutcome outcome = investigationAgent.investigateSafely(bankingCase);
        BankingCase c = caseRepository.findById(bankingCase.getId()).orElseThrow();
        InvestigationResult result = outcome.result();
        c.setInvestigationStatus(outcome.status());
        c.setInvestigationSummary(result == null ? null : result.summary());
        c.setPossibleCause(result == null ? null : result.possibleCause());
        c.setInvestigationEvidenceIds(result == null ? List.of() : result.evidenceIds());
        c.setRequiresHumanReview(result == null ? Boolean.TRUE : result.requiresHumanReview());
        c.setInvestigationError(outcome.error());
        c.setInvestigatedAt(Instant.now());
        caseRepository.save(c);
        auditService.record(c.getBatchId(), c.getId(), "INVESTIGATION_" + outcome.status(), InvestigationTools.AGENT,
                outcome.error() != null ? outcome.error() : result == null ? null : result.possibleCause());
        return outcome.status();
    }

    /** POST /api/cases/{id}/investigate: rerun the investigation, then re-apply the bound policy version. */
    public BankingCase reinvestigate(String caseId) {
        BankingCase c = caseRepository.findById(caseId).orElseThrow(() -> ApiException.notFound("Case", caseId));
        if (running.contains(c.getBatchId())) {
            throw ApiException.conflict("Batch " + c.getBatchId() + " is still being processed");
        }
        runInvestigation(c);
        Integer versionId = c.getPolicyVersionId() != null ? c.getPolicyVersionId()
                : batchService.getBatch(c.getBatchId()).getPolicyVersionId();
        if (versionId != null) {
            decide(caseRepository.findById(caseId).orElseThrow(), policy(versionId));
        }
        return caseRepository.findById(caseId).orElseThrow();
    }

    // ------------------------------------------------------------------ APPLYING_POLICY

    private String applyPolicy(String batchId, PolicyVersion policy) {
        int auto = 0;
        int review = 0;
        for (BankingCase c : caseRepository.findByBatchIdOrderByIdAsc(batchId)) {
            if (decide(c, policy) == CaseStatus.AUTO_MATCHED) {
                auto++;
            } else {
                review++;
            }
        }
        return "policy v" + policy.getId() + ": " + auto + " auto-matched, " + review + " require review";
    }

    private CaseStatus decide(BankingCase c, PolicyVersion policy) {
        PolicyDecision decision = policyAgent.evaluate(Discrepancy.fromCase(c), policy);
        boolean changed = c.getStatus() != decision.status()
                || !Objects.equals(c.getPolicyVersionId(), decision.policyVersionId())
                || !c.getAppliedRuleList().equals(decision.appliedRules());
        if (!changed) {
            return c.getStatus();
        }
        CaseStatus previous = c.getStatus();
        c.setStatus(decision.status());
        c.setPolicyVersionId(decision.policyVersionId());
        c.setAppliedRules(decision.appliedRules());
        c.setDecisionReason(decision.reason());
        c.setDecidedAt(Instant.now());
        if (c.getReviewStatus() == ReviewStatus.PENDING || c.getReviewStatus() == ReviewStatus.NOT_REQUIRED) {
            c.setReviewStatus(decision.status() == CaseStatus.REVIEW_REQUIRED ? ReviewStatus.PENDING : ReviewStatus.NOT_REQUIRED);
        }
        caseRepository.save(c);
        auditService.record(c.getBatchId(), c.getId(), "POLICY_DECISION", PolicyAgent.class.getSimpleName(),
                previous + " -> " + decision.status() + " under policy v" + decision.policyVersionId() + " "
                        + decision.appliedRules() + ": " + decision.reason());
        return decision.status();
    }
}
