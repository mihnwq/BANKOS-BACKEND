package com.bankos.dto;

import com.bankos.model.BankingCase;
import com.bankos.model.CaseStatus;
import com.bankos.model.CaseType;
import com.bankos.model.InvestigationStatus;
import com.bankos.model.ReviewStatus;

import java.math.BigDecimal;

public record CaseSummary(
        String id,
        String batchId,
        CaseType type,
        String subjectId,
        String paymentId,
        BigDecimal amount,
        BigDecimal difference,
        String currency,
        CaseStatus status,
        Integer policyVersion,
        InvestigationStatus investigationStatus,
        ReviewStatus reviewStatus,
        String description
) {

    public static CaseSummary from(BankingCase c) {
        return new CaseSummary(c.getId(), c.getBatchId(), c.getType(), c.getSubjectId(), c.getPaymentId(),
                c.getExpectedAmount() != null ? c.getExpectedAmount() : c.getActualAmount(),
                c.getDifference(), c.getCurrency(), c.getStatus(), c.getPolicyVersionId(),
                c.getInvestigationStatus(), c.getReviewStatus(), c.getDescription());
    }
}
