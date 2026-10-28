package com.bankos.model;

/** Batch workflow states: UPLOADED -> PARSING -> RECONCILING -> INVESTIGATING -> APPLYING_POLICY -> READY_FOR_REVIEW. */
public enum BatchStatus {
    UPLOADED,
    PARSING,
    RECONCILING,
    INVESTIGATING,
    APPLYING_POLICY,
    READY_FOR_REVIEW,
    FAILED;

    public boolean isRunning() {
        return this == PARSING || this == RECONCILING || this == INVESTIGATING || this == APPLYING_POLICY;
    }
}
