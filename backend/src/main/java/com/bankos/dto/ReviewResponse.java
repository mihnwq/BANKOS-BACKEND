package com.bankos.dto;

import com.bankos.model.ReviewStatus;

import java.time.Instant;

public record ReviewResponse(
        Long auditEventId,
        String caseId,
        ReviewStatus reviewStatus,
        String actor,
        String note,
        Instant createdAt
) {}
