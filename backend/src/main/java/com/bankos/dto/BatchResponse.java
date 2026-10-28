package com.bankos.dto;

import com.bankos.model.Batch;
import com.bankos.model.BatchStatus;

import java.time.Instant;
import java.util.Map;

public record BatchResponse(
        String id,
        BatchStatus status,
        Instant createdAt,
        Instant processedAt,
        Long durationMs,
        Integer policyVersion,
        String paymentsFile,
        String settlementsFile,
        Totals totals,
        Map<String, Long> casesByStatus,
        String error
) {

    public record Totals(
            int payments,
            int settlements,
            int invoices,
            int matchedPayments,
            int quarantinedRows,
            long cases
    ) {}

    public static BatchResponse from(Batch b, long caseCount, Map<String, Long> casesByStatus) {
        return new BatchResponse(b.getId(), b.getStatus(), b.getCreatedAt(), b.getProcessedAt(), b.getDurationMs(),
                b.getPolicyVersionId(), b.getPaymentsFile(), b.getSettlementsFile(),
                new Totals(b.getPaymentCount(), b.getSettlementCount(), b.getInvoiceCount(),
                        b.getMatchedPaymentCount(), b.getQuarantinedRowCount(), caseCount),
                casesByStatus, b.getErrorMessage());
    }
}
