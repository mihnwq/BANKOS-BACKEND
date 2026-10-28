package com.bankos;

import com.bankos.agent.ReconciliationAgent;
import com.bankos.dto.Discrepancy;
import com.bankos.model.CaseType;
import com.bankos.model.Invoice;
import com.bankos.model.InvoiceStatus;
import com.bankos.model.Payment;
import com.bankos.model.Settlement;
import com.bankos.model.SettlementStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationAgentTest {

    private final ReconciliationAgent agent = new ReconciliationAgent();
    private final AtomicInteger row = new AtomicInteger();

    @Test
    void exactMatchProducesNoException() {
        List<Discrepancy> result = agent.reconcile(
                List.of(payment("PAY-1", "RF-1", "100.00", "EUR")),
                List.of(settlement("SET-1", "PAY-1", "RF-1", "100.00", "EUR", SettlementStatus.SETTLED)));

        assertThat(result).isEmpty();
    }

    @Test
    void bigDecimalScaleDoesNotCreateFalseMismatch() {
        List<Discrepancy> result = agent.reconcile(
                List.of(payment("PAY-1", "RF-1", "100.0", "EUR")),
                List.of(settlement("SET-1", "PAY-1", "rf 1", "100.00", "EUR", SettlementStatus.SETTLED)));

        assertThat(result).isEmpty();
    }

    @Test
    void missingSettlementCreatesCase() {
        List<Discrepancy> result = agent.reconcile(List.of(payment("PAY-1", "RF-1", "100.00", "EUR")), List.of());

        assertThat(result).singleElement().satisfies(d -> {
            assertThat(d.type()).isEqualTo(CaseType.MISSING_SETTLEMENT);
            assertThat(d.subjectId()).isEqualTo("PAY-1");
            assertThat(d.sources()).extracting(Discrepancy.SourceRef::recordId).containsExactly("PAY-1");
        });
    }

    @Test
    void pendingOrReversedSettlementIsNotACompletedPayment() {
        List<Discrepancy> result = agent.reconcile(
                List.of(payment("PAY-1", "RF-1", "100.00", "EUR"), payment("PAY-2", "RF-2", "50.00", "EUR")),
                List.of(settlement("SET-1", "PAY-1", "RF-1", "100.00", "EUR", SettlementStatus.PENDING),
                        settlement("SET-2", "PAY-2", "RF-2", "50.00", "EUR", SettlementStatus.REVERSED)));

        assertThat(result).extracting(Discrepancy::type)
                .containsExactly(CaseType.MISSING_SETTLEMENT, CaseType.MISSING_SETTLEMENT);
        assertThat(result.getFirst().description()).contains("PENDING");
    }

    @Test
    void twoDistinctSettledIdsAreADuplicateSettlement() {
        List<Discrepancy> result = agent.reconcile(
                List.of(payment("PAY-1042", "RF-1042", "48000.00", "EUR")),
                List.of(settlement("SET-1042-A", "PAY-1042", "RF-1042", "48000.00", "EUR", SettlementStatus.SETTLED),
                        settlement("SET-1042-B", "PAY-1042", "RF-1042", "48000.00", "EUR", SettlementStatus.SETTLED)));

        assertThat(result).singleElement().satisfies(d -> {
            assertThat(d.type()).isEqualTo(CaseType.DUPLICATE_SETTLEMENT);
            assertThat(d.settlementIds()).containsExactly("SET-1042-A", "SET-1042-B");
            assertThat(d.actualAmount()).isEqualByComparingTo("96000.00");
            assertThat(d.difference()).isEqualByComparingTo("48000.00");
            assertThat(d.sources()).hasSize(3);
        });
    }

    @Test
    void repeatedExportRowIsNotADuplicateSettlement() {
        Settlement row = settlement("SET-7", "PAY-7", "RF-7", "70.00", "EUR", SettlementStatus.SETTLED);
        Settlement repeated = settlement("SET-7", "PAY-7", "RF-7", "70.00", "EUR", SettlementStatus.SETTLED);

        List<Discrepancy> result = agent.reconcile(List.of(payment("PAY-7", "RF-7", "70.00", "EUR")), List.of(row, repeated));

        assertThat(result).singleElement().satisfies(d -> {
            assertThat(d.type()).isEqualTo(CaseType.EXPORT_DUPLICATE);
            assertThat(d.difference()).as("identical rows").isNull();
        });
    }

    @Test
    void conflictingRepeatedExportRowsAreFlaggedAsInconsistent() {
        List<Discrepancy> result = agent.reconcile(List.of(payment("PAY-7", "RF-7", "70.00", "EUR")),
                List.of(settlement("SET-7", "PAY-7", "RF-7", "70.00", "EUR", SettlementStatus.SETTLED),
                        settlement("SET-7", "PAY-7", "RF-7", "75.00", "EUR", SettlementStatus.SETTLED)));

        assertThat(result).singleElement().satisfies(d -> {
            assertThat(d.type()).isEqualTo(CaseType.EXPORT_DUPLICATE);
            assertThat(d.difference()).isEqualByComparingTo("5.00");
        });
    }

    @Test
    void amountMismatchIsComputedWithBigDecimal() {
        List<Discrepancy> result = agent.reconcile(
                List.of(payment("PAY-1", "RF-1", "2340.00", "EUR")),
                List.of(settlement("SET-1", "PAY-1", "RF-1", "2320.00", "EUR", SettlementStatus.SETTLED)));

        assertThat(result).singleElement().satisfies(d -> {
            assertThat(d.type()).isEqualTo(CaseType.AMOUNT_MISMATCH);
            assertThat(d.difference()).isEqualByComparingTo("-20.00");
        });
    }

    @Test
    void differentCurrenciesAreNeverComparedAsEqual() {
        List<Discrepancy> result = agent.reconcile(
                List.of(payment("PAY-1", "RF-1", "100.00", "EUR")),
                List.of(settlement("SET-1", "PAY-1", "RF-1", "100.00", "USD", SettlementStatus.SETTLED)));

        assertThat(result).extracting(Discrepancy::type).containsExactly(CaseType.CURRENCY_MISMATCH);
    }

    @Test
    void referenceMismatchIsSeparateFinding() {
        List<Discrepancy> result = agent.reconcile(
                List.of(payment("PAY-1", "RF-1", "100.00", "EUR")),
                List.of(settlement("SET-1", "PAY-1", "RF-999", "100.00", "EUR", SettlementStatus.SETTLED)));

        assertThat(result).extracting(Discrepancy::type).containsExactly(CaseType.REFERENCE_MISMATCH);
    }

    @Test
    void settlementWithoutKnownPaymentIsUnexpected() {
        List<Discrepancy> result = agent.reconcile(
                List.of(payment("PAY-1", "RF-1", "100.00", "EUR")),
                List.of(settlement("SET-1", "PAY-1", "RF-1", "100.00", "EUR", SettlementStatus.SETTLED),
                        settlement("SET-9", "PAY-9999", "RF-X", "3333.33", "EUR", SettlementStatus.SETTLED)));

        assertThat(result).singleElement().satisfies(d -> {
            assertThat(d.type()).isEqualTo(CaseType.UNEXPECTED_SETTLEMENT);
            assertThat(d.subjectId()).isEqualTo("SET-9");
        });
    }

    @Test
    void ambiguousCandidatesAreNeverSilentlyResolved() {
        List<Discrepancy> result = agent.reconcile(
                List.of(payment("PAY-1150", "RF-1150", "12500.00", "EUR"), payment("PAY-1151", "RF-1151", "12500.00", "EUR")),
                List.of(settlement("SET-9002", null, "RF-LOST", "12500.00", "EUR", SettlementStatus.SETTLED)));

        assertThat(result).singleElement().satisfies(d -> {
            assertThat(d.type()).isEqualTo(CaseType.AMBIGUOUS_MATCH);
            assertThat(d.sources()).extracting(Discrepancy.SourceRef::recordId)
                    .containsExactly("SET-9002", "PAY-1150", "PAY-1151");
        });
    }

    @Test
    void settlementWithoutPaymentIdLinksOnUniqueReference() {
        List<Discrepancy> result = agent.reconcile(
                List.of(payment("PAY-1033", "RF-261033", "990.00", "EUR")),
                List.of(settlement("SET-1033", "", "RF 261033", "990.00", "EUR", SettlementStatus.SETTLED)));

        assertThat(result).isEmpty();
    }

    @Test
    void duplicatePaymentRowsInRegisterAreDetected() {
        Payment first = payment("PAY-1180", "RF-1180", "10.00", "EUR");
        Payment again = payment("PAY-1180", "RF-1180", "10.00", "EUR");

        List<Discrepancy> result = agent.reconcile(List.of(first, again),
                List.of(settlement("SET-1180", "PAY-1180", "RF-1180", "10.00", "EUR", SettlementStatus.SETTLED)));

        assertThat(result).extracting(Discrepancy::type).containsExactly(CaseType.DUPLICATE_PAYMENT);
    }

    @Test
    void invoiceChecks() {
        Payment absent = invoicedPayment("PAY-1", "RF-1", "100.00", "INV-1");
        Payment unreadable = invoicedPayment("PAY-2", "RF-2", "200.00", "INV-2");
        Payment wrongAmount = invoicedPayment("PAY-3", "RF-3", "300.00", "INV-3");
        Payment wrongReference = invoicedPayment("PAY-4", "RF-4", "400.00", "INV-4");
        Payment clean = invoicedPayment("PAY-5", "RF-5", "500.00", "INV-5");

        Invoice unreadableInvoice = new Invoice("B1", "INV-2.pdf");
        unreadableInvoice.setStatus(InvoiceStatus.UNREADABLE);
        List<Invoice> invoices = List.of(unreadableInvoice,
                invoice("INV-3", "350.00", "RF-3"), invoice("INV-4", "400.00", "RF-40"), invoice("INV-5", "500.00", "RF-5"));

        List<Discrepancy> result = agent.reconcileInvoices(List.of(absent, unreadable, wrongAmount, wrongReference, clean), invoices);

        assertThat(result).extracting(Discrepancy::type, Discrepancy::subjectId).containsExactly(
                org.assertj.core.groups.Tuple.tuple(CaseType.MISSING_INVOICE, "PAY-1"),
                org.assertj.core.groups.Tuple.tuple(CaseType.MISSING_INVOICE, "PAY-2"),
                org.assertj.core.groups.Tuple.tuple(CaseType.INVOICE_MISMATCH, "PAY-3"),
                org.assertj.core.groups.Tuple.tuple(CaseType.INVOICE_MISMATCH, "PAY-4"));
        assertThat(result.get(2).difference()).isEqualByComparingTo("50.00");
        assertThat(result.get(3).sources()).anySatisfy(s -> {
            assertThat(s.pageNumber()).isEqualTo(1);
            assertThat(s.value()).isEqualTo("RF-40");
        });
    }

    private Payment payment(String id, String ref, String amount, String currency) {
        return new Payment("B1", id, ref, "Supplier", new BigDecimal(amount), currency, LocalDate.of(2026, 9, 1),
                null, row.incrementAndGet(), "payments.csv");
    }

    private Payment invoicedPayment(String id, String ref, String amount, String invoice) {
        return new Payment("B1", id, ref, "Supplier", new BigDecimal(amount), "EUR", LocalDate.of(2026, 9, 1),
                invoice, row.incrementAndGet(), "payments.csv");
    }

    private Settlement settlement(String id, String paymentId, String ref, String amount, String currency,
                                  SettlementStatus status) {
        return new Settlement("B1", id, paymentId, ref, new BigDecimal(amount), currency, status,
                LocalDate.of(2026, 9, 2), row.incrementAndGet(), "settlements.csv");
    }

    private static Invoice invoice(String number, String amount, String paymentReference) {
        Invoice invoice = new Invoice("B1", number + ".pdf");
        invoice.setNumber(number);
        invoice.setAmount(new BigDecimal(amount));
        invoice.setCurrency("EUR");
        invoice.setPaymentReference(paymentReference);
        invoice.setSourcePage(1);
        invoice.setStatus(InvoiceStatus.VERIFIED);
        return invoice;
    }
}
