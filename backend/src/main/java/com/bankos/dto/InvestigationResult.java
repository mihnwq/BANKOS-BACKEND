package com.bankos.dto;

import java.util.List;

public record InvestigationResult(
        String summary,
        String possibleCause,
        List<String> evidenceIds,
        boolean requiresHumanReview
) {}
