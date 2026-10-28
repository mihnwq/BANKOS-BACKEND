package com.bankos;

import com.bankos.agent.DocumentExtractionAgent;
import com.bankos.config.AiRuntime;
import com.bankos.config.BankosProperties;
import com.bankos.datagen.SyntheticDataGenerator;
import com.bankos.dto.InvoiceData;
import com.bankos.model.Invoice;
import com.bankos.model.InvoiceStatus;
import com.bankos.service.AuditService;
import com.bankos.service.PdfService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DocumentExtractionAgentTest {

    @TempDir
    static Path data;

    private static ExecutorService executor;
    private static DocumentExtractionAgent agent;
    private static final PdfService pdfService = new PdfService();

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void setUp() throws Exception {
        new SyntheticDataGenerator(SyntheticDataGenerator.DEFAULT_SEED).generate(data);
        executor = Executors.newSingleThreadExecutor();
        AiRuntime offline = new AiRuntime(BankosProperties.AiMode.OFFLINE, Duration.ofSeconds(5), 4, executor);
        agent = new DocumentExtractionAgent(mock(ObjectProvider.class), offline, mock(AuditService.class));
    }

    @AfterAll
    static void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void extractsAndVerifiesRealPdfWithPageProvenance() throws Exception {
        Invoice invoice = agent.extractInvoice("B1", "INV-2042.pdf",
                pdfService.extractPages(data.resolve("invoices/INV-2042.pdf")));

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.VERIFIED);
        assertThat(invoice.getNumber()).isEqualTo("INV-2042");
        assertThat(invoice.getAmount()).isEqualByComparingTo("48000.00");
        assertThat(invoice.getCurrency()).isEqualTo("EUR");
        assertThat(invoice.getPaymentReference()).isEqualTo("RF-260024");
        assertThat(invoice.getSourcePage()).isEqualTo(1);
        assertThat(invoice.getExtractionMethod()).isEqualTo(DocumentExtractionAgent.METHOD_OFFLINE);
    }

    @Test
    void scannedPdfWithoutTextIsUnreadableAndNothingIsInvented() throws Exception {
        Invoice invoice = agent.extractInvoice("B1", "INV-2090.pdf",
                pdfService.extractPages(data.resolve("invoices/INV-2090.pdf")));

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.UNREADABLE);
        assertThat(invoice.getNumber()).isNull();
        assertThat(invoice.getAmount()).isNull();
    }

    @Test
    void promptInjectionInsideTheDocumentIsTreatedAsData() throws Exception {
        Invoice invoice = agent.extractInvoice("B1", "INV-2070.pdf",
                pdfService.extractPages(data.resolve("invoices/INV-2070.pdf")));

        assertThat(invoice.getPageText()).containsIgnoringCase("ignore all previous instructions");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.VERIFIED);
        assertThat(invoice.getNumber()).isEqualTo("INV-2070");
    }

    @Test
    void incorrectExtractedFieldIsRejectedNotSilentlyAccepted() {
        String page = """
                INVOICE
                Invoice Number: INV-2042
                Supplier: Nordwind Logistics GmbH
                Payment Reference: RF-261042
                Total Amount: 48,000.00 EUR
                Currency: EUR
                """;
        InvoiceData hallucinated = new InvoiceData("INV-2042", "Nordwind Logistics GmbH",
                new BigDecimal("84000.00"), "EUR", "RF-999999", 1);

        assertThat(agent.verify(hallucinated, page))
                .anyMatch(p -> p.contains("amount 84000.00"))
                .anyMatch(p -> p.contains("RF-999999"));
        assertThat(agent.verify(agent.parse(page, 1), page)).isEmpty();
    }

    @Test
    void liveModeWithoutModelFallsBackToLabelledParser() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatClient.Builder> none = mock(ObjectProvider.class);
        AiRuntime live = new AiRuntime(BankosProperties.AiMode.LIVE, Duration.ofSeconds(5), 4, executor);
        DocumentExtractionAgent liveAgent = new DocumentExtractionAgent(none, live, mock(AuditService.class));

        Invoice invoice = liveAgent.extractInvoice("B1", "INV-2010.pdf",
                pdfService.extractPages(data.resolve("invoices/INV-2010.pdf")));

        assertThat(invoice.getExtractionMethod()).isEqualTo(DocumentExtractionAgent.METHOD_FALLBACK);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.VERIFIED);
    }
}
