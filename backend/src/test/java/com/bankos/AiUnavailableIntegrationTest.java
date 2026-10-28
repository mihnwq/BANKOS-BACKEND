package com.bankos;

import com.bankos.agent.AgentOrchestrator;
import com.bankos.datagen.SyntheticDataGenerator;
import com.bankos.model.BankingCase;
import com.bankos.model.Batch;
import com.bankos.model.BatchStatus;
import com.bankos.model.CaseStatus;
import com.bankos.model.InvestigationStatus;
import com.bankos.model.Invoice;
import com.bankos.repository.CaseRepository;
import com.bankos.repository.InvoiceRepository;
import com.bankos.service.BatchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live mode without a reachable model: deterministic reconciliation results are preserved, every
 * case is escalated to REVIEW_REQUIRED and invoice extraction falls back to the labelled parser.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:bankos-ai-down;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.ai.model.chat=none",
        "bankos.ai.mode=live",
        "bankos.processing.async=false"
})
class AiUnavailableIntegrationTest {

    static final Path DATA;

    static {
        try {
            DATA = Files.createTempDirectory("bankos-ai-down");
            new SyntheticDataGenerator(SyntheticDataGenerator.DEFAULT_SEED).generate(DATA);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("bankos.data.policies-file", () -> DATA.resolve("policies.json").toString());
        registry.add("bankos.data.ground-truth-file", () -> DATA.resolve("ground_truth.json").toString());
        registry.add("bankos.storage.upload-dir", () -> DATA.resolve("uploads").toString());
    }

    @Autowired
    BatchService batchService;
    @Autowired
    AgentOrchestrator orchestrator;
    @Autowired
    CaseRepository caseRepository;
    @Autowired
    InvoiceRepository invoiceRepository;

    @Test
    void aiOutageEscalatesToReviewAndPreservesDeterministicFindings() throws IOException {
        List<MultipartFile> pdfs;
        try (Stream<Path> files = Files.list(DATA.resolve("invoices"))) {
            pdfs = files.sorted().map(AiUnavailableIntegrationTest::file).toList();
        }
        Batch batch = batchService.createBatch(file(DATA.resolve("payments.csv")),
                file(DATA.resolve("settlements.csv")), pdfs);

        Batch processed = orchestrator.start(batch.getId(), null);

        assertThat(processed.getStatus()).isEqualTo(BatchStatus.READY_FOR_REVIEW);
        List<BankingCase> cases = caseRepository.findByBatchIdOrderByIdAsc(batch.getId());
        assertThat(cases).hasSize(20);
        assertThat(cases).allSatisfy(c -> {
            assertThat(c.getInvestigationStatus()).isEqualTo(InvestigationStatus.FAILED);
            assertThat(c.getStatus()).isEqualTo(CaseStatus.REVIEW_REQUIRED);
            assertThat(c.getAppliedRuleList()).contains("RULE_AI_UNAVAILABLE");
        });
        // The 7.50 EUR difference would be auto-matched by policy alone; the AI outage escalates it.
        BankingCase borderline = caseRepository.findById("CASE-" + batch.getId() + "-PAY-1188-AMT").orElseThrow();
        assertThat(borderline.getDifference()).isEqualByComparingTo("7.50");
        assertThat(borderline.getDecisionReason()).contains("Escalated");

        List<Invoice> invoices = invoiceRepository.findByBatchIdOrderBySourceFileAsc(batch.getId());
        assertThat(invoices).filteredOn(i -> i.getPageText() != null)
                .allSatisfy(i -> assertThat(i.getExtractionMethod()).isEqualTo("FALLBACK_PARSER"));
    }

    private static MultipartFile file(Path path) {
        try {
            return new MockMultipartFile(path.getFileName().toString(), path.getFileName().toString(),
                    null, Files.readAllBytes(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
