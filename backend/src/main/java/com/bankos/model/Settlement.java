package com.bankos.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A simulated settlement outcome (one row of settlements.csv). */
@Entity
@Table(name = "settlements", indexes = @Index(columnList = "batchId"))
public class Settlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String batchId;

    /** Business identifier from the source file, e.g. SET-1042-A. */
    @Column(nullable = false)
    private String settlementId;

    /** Payment the settlement claims to belong to; may be blank or unknown. */
    private String paymentId;

    private String reference;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SettlementStatus status;

    private LocalDate settledAt;

    private int rowNumber;
    private String sourceFile;

    protected Settlement() {
    }

    public Settlement(String batchId, String settlementId, String paymentId, String reference, BigDecimal amount,
                      String currency, SettlementStatus status, LocalDate settledAt, int rowNumber, String sourceFile) {
        this.batchId = batchId;
        this.settlementId = settlementId;
        this.paymentId = paymentId;
        this.reference = reference;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.settledAt = settledAt;
        this.rowNumber = rowNumber;
        this.sourceFile = sourceFile;
    }

    public Long getId() {
        return id;
    }

    public String getBatchId() {
        return batchId;
    }

    public String getSettlementId() {
        return settlementId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getReference() {
        return reference;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public SettlementStatus getStatus() {
        return status;
    }

    public LocalDate getSettledAt() {
        return settledAt;
    }

    public int getRowNumber() {
        return rowNumber;
    }

    public String getSourceFile() {
        return sourceFile;
    }
}
