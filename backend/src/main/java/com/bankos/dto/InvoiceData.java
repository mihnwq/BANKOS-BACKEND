package com.bankos.dto;

import java.math.BigDecimal;

public record InvoiceData(
        String invoiceNumber,
        String supplier,
        BigDecimal amount,
        String currency,
        String paymentReference,
        int sourcePage
) {

    /** The page number always comes from PdfService, never from the model. */
    public InvoiceData withSourcePage(int page) {
        return new InvoiceData(invoiceNumber, supplier, amount, currency, paymentReference, page);
    }
}
