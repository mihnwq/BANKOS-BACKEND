package com.bankos.model;

public enum InvestigationStatus {
    /** Not investigated yet (e.g. beyond the per-batch investigation budget). */
    NOT_RUN,
    /** Live model investigation with verified evidence IDs and real tool calls. */
    COMPLETED,
    /** Deterministic offline investigator (no model connectivity), clearly labelled. */
    OFFLINE_FALLBACK,
    /** Model unavailable, timed out or returned unusable output. Escalates to review. */
    FAILED,
    /** Model output cited unknown evidence or skipped the required tool. Escalates to review. */
    REJECTED
}
