package com.bankos.dto;

import com.bankos.model.CaseStatus;

import java.util.List;

/**
 * Deterministic outcome of the PolicyAgent. The policy version ID is saved with every outcome.
 *
 * @param appliedRules rule identifiers that fired, e.g. RULE_AMOUNT_TOLERANCE
 */
public record PolicyDecision(
        CaseStatus status,
        List<String> appliedRules,
        String reason,
        Integer policyVersionId
) {}
