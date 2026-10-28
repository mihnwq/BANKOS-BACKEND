package com.bankos.model;

public enum InvoiceStatus {
    /** Extracted fields were cross-checked against the page text. */
    VERIFIED,
    /** Extracted values are not supported by the page text; not usable as evidence. */
    REJECTED,
    /** PDF has no selectable text (e.g. a scan). OCR is out of scope. */
    UNREADABLE,
    /** Extraction failed. */
    FAILED
}
