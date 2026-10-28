package com.bankos.dto;

import com.bankos.model.CaseStatus;
import com.bankos.model.CaseType;

import java.math.BigDecimal;
import java.util.List;

/** Versioned before/after comparison. Simulation only: stored case decisions are unchanged. */
public record PolicyReplayResult(
        PolicyResponse baseVersion,
        PolicyResponse newVersion,
        String batchId,
        int evaluatedCases,
        int changedCases,
        List<CaseChange> changes,
        boolean simulationOnly
) {

    public record CaseChange(
            String caseId,
            CaseType type,
            String subjectId,
            BigDecimal difference,
            String currency,
            Integer beforeVersion,
            CaseStatus beforeStatus,
            List<String> beforeRules,
            CaseStatus afterStatus,
            List<String> afterRules,
            String afterReason
    ) {}
}
