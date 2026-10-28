package com.bankos.dto;

import com.bankos.model.CaseStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Proposed rule changes. Null fields inherit from the base version. Replay creates a new immutable
 * policy version and re-evaluates stored cases; stored decisions are never overwritten.
 *
 * @param batchId     restrict the replay to one batch; null means all stored cases
 * @param baseVersion version to derive from; null means the version each case was decided under
 */
public record PolicyReplayRequest(
        String batchId,
        Integer baseVersion,
        @DecimalMin("0.00") BigDecimal amountTolerance,
        Boolean requireInvoice,
        Boolean duplicatePaymentAllowed,
        CaseStatus missingSettlement,
        CaseStatus ambiguousReference,
        CaseStatus unexpectedSettlement,
        CaseStatus referenceMismatch,
        CaseStatus exportDuplicate,
        @Size(max = 500) String description
) {}
