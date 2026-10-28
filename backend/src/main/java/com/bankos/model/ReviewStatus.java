package com.bankos.model;

/** Human reviewer state. Kept separate from detection facts and policy status. */
public enum ReviewStatus {
    NOT_REQUIRED,
    PENDING,
    INVESTIGATING,
    APPROVED,
    REJECTED,
    ESCALATED
}
