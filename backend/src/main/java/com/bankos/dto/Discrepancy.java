package com.bankos.dto;

import com.bankos.model.BankingCase;
import com.bankos.model.CaseType;

import java.math.BigDecimal;
import java.util.List;

/**
 * A deterministic finding produced by the ReconciliationAgent and evaluated by the PolicyAgent.
 * All monetary values are BigDecimal; {@code difference} is actual minus expected.
 *
 * @param subjectId        payment ID, or settlement ID for settlement-only findings
 * @param invoiceRequired  the payment declares a supporting invoice
 * @param invoiceAvailable a verified invoice was found for it
 * @param aiFailure        the AI step for this case failed or was rejected by verification
 * @param sources          exact source facts (CSV rows / PDF pages) supporting the finding
 */
public record Discrepancy(
        CaseType type,
        String subjectId,
        String paymentId,
        List<String> settlementIds,
        String reference,
        String invoiceNumber,
        BigDecimal expectedAmount,
        BigDecimal actualAmount,
        BigDecimal difference,
        String currency,
        String description,
        boolean invoiceRequired,
        boolean invoiceAvailable,
        boolean aiFailure,
        List<SourceRef> sources
) {

    public record SourceRef(
            String source,
            Integer rowNumber,
            Integer pageNumber,
            String field,
            String recordId,
            String value
    ) {}

    /** Rebuilds the policy input from a stored case (used for replay and re-evaluation). */
    public static Discrepancy fromCase(BankingCase c) {
        return new Discrepancy(c.getType(), c.getSubjectId(), c.getPaymentId(), c.getSettlementIdList(),
                c.getReference(), c.getInvoiceNumber(), c.getExpectedAmount(), c.getActualAmount(),
                c.getDifference(), c.getCurrency(), c.getDescription(), c.isInvoiceRequired(),
                c.isInvoiceAvailable(), c.isAiFailure(), List.of());
    }
}
