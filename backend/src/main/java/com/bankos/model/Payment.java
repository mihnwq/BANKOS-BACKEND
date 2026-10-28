package com.bankos.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/** An intended outgoing payment instruction (one row of payments.csv). */
@Entity
@Table(name = "payments", indexes = @Index(columnList = "batchId"))
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String batchId;

    /** Business identifier from the source file, e.g. PAY-1042. */
    @Column(nullable = false)
    private String paymentId;

    private String reference;
    private String supplier;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    private LocalDate valueDate;
    private String invoiceNumber;

    /** 1-based data row in the source CSV (header excluded). */
    private int rowNumber;
    private String sourceFile;

    protected Payment() {
    }

    public Payment(String batchId, String paymentId, String reference, String supplier, BigDecimal amount,
                   String currency, LocalDate valueDate, String invoiceNumber, int rowNumber, String sourceFile) {
        this.batchId = batchId;
        this.paymentId = paymentId;
        this.reference = reference;
        this.supplier = supplier;
        this.amount = amount;
        this.currency = currency;
        this.valueDate = valueDate;
        this.invoiceNumber = invoiceNumber;
        this.rowNumber = rowNumber;
        this.sourceFile = sourceFile;
    }

    public Long getId() {
        return id;
    }

    public String getBatchId() {
        return batchId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getReference() {
        return reference;
    }

    public String getSupplier() {
        return supplier;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getValueDate() {
        return valueDate;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public int getRowNumber() {
        return rowNumber;
    }

    public String getSourceFile() {
        return sourceFile;
    }
}
