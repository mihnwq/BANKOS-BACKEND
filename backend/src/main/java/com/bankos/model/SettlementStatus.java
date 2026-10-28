package com.bankos.model;

/** Only SETTLED counts as a completed payment; PENDING and REVERSED never do. */
public enum SettlementStatus {
    SETTLED,
    PENDING,
    REVERSED
}
