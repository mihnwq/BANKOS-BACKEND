package com.bankos.dto;

import com.bankos.model.CaseStatus;
import com.bankos.model.PolicyVersion;

import java.math.BigDecimal;
import java.time.Instant;

public record PolicyResponse(
        Integer version,
        String currency,
        BigDecimal amountTolerance,
        boolean requireInvoice,
        boolean duplicatePaymentAllowed,
        CaseStatus missingSettlement,
        CaseStatus ambiguousReference,
        CaseStatus unexpectedSettlement,
        CaseStatus referenceMismatch,
        CaseStatus exportDuplicate,
        Integer basedOn,
        String description,
        Instant createdAt
) {

    public static PolicyResponse from(PolicyVersion p) {
        return new PolicyResponse(p.getId(), p.getCurrency(), p.getTolerance(), p.isRequireInvoice(),
                p.isDuplicateAllowed(), p.getMissingSettlement(), p.getAmbiguousReference(),
                p.getUnexpectedSettlement(), p.getReferenceMismatch(), p.getExportDuplicate(),
                p.getBasedOn(), p.getDescription(), p.getCreatedAt());
    }
}
