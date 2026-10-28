package com.bankos.model;

/** Discrepancy categories detected by deterministic Java logic. */
public enum CaseType {
    MISSING_SETTLEMENT("MISS"),
    DUPLICATE_SETTLEMENT("DUP"),
    DUPLICATE_PAYMENT("DUPPAY"),
    EXPORT_DUPLICATE("EXPDUP"),
    AMOUNT_MISMATCH("AMT"),
    CURRENCY_MISMATCH("CCY"),
    REFERENCE_MISMATCH("REF"),
    UNEXPECTED_SETTLEMENT("UNEXP"),
    AMBIGUOUS_MATCH("AMB"),
    MISSING_INVOICE("NOINV"),
    INVOICE_MISMATCH("INVMIS");

    private final String code;

    CaseType(String code) {
        this.code = code;
    }

    /** Short code used to build stable, human-readable case IDs. */
    public String code() {
        return code;
    }
}
