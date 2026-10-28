package com.bankos.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "batches")
public class Batch {

    @Id
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BatchStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant processingStartedAt;
    private Instant processedAt;
    private Long durationMs;

    /** Policy version bound to this batch for its whole run (no cross-version mixed decisions). */
    private Integer policyVersionId;

    private String paymentsFile;
    private String settlementsFile;
    private String paymentsSha256;
    private int invoiceFileCount;

    private int paymentCount;
    private int settlementCount;
    private int invoiceCount;
    private int matchedPaymentCount;
    private int quarantinedRowCount;

    @Column(length = 2000)
    private String errorMessage;

    protected Batch() {
    }

    public Batch(String id) {
        this.id = id;
        this.status = BatchStatus.UPLOADED;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public BatchStatus getStatus() {
        return status;
    }

    public void setStatus(BatchStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getProcessingStartedAt() {
        return processingStartedAt;
    }

    public void setProcessingStartedAt(Instant processingStartedAt) {
        this.processingStartedAt = processingStartedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Integer getPolicyVersionId() {
        return policyVersionId;
    }

    public void setPolicyVersionId(Integer policyVersionId) {
        this.policyVersionId = policyVersionId;
    }

    public String getPaymentsFile() {
        return paymentsFile;
    }

    public void setPaymentsFile(String paymentsFile) {
        this.paymentsFile = paymentsFile;
    }

    public String getSettlementsFile() {
        return settlementsFile;
    }

    public void setSettlementsFile(String settlementsFile) {
        this.settlementsFile = settlementsFile;
    }

    public String getPaymentsSha256() {
        return paymentsSha256;
    }

    public void setPaymentsSha256(String paymentsSha256) {
        this.paymentsSha256 = paymentsSha256;
    }

    public int getInvoiceFileCount() {
        return invoiceFileCount;
    }

    public void setInvoiceFileCount(int invoiceFileCount) {
        this.invoiceFileCount = invoiceFileCount;
    }

    public int getPaymentCount() {
        return paymentCount;
    }

    public void setPaymentCount(int paymentCount) {
        this.paymentCount = paymentCount;
    }

    public int getSettlementCount() {
        return settlementCount;
    }

    public void setSettlementCount(int settlementCount) {
        this.settlementCount = settlementCount;
    }

    public int getInvoiceCount() {
        return invoiceCount;
    }

    public void setInvoiceCount(int invoiceCount) {
        this.invoiceCount = invoiceCount;
    }

    public int getMatchedPaymentCount() {
        return matchedPaymentCount;
    }

    public void setMatchedPaymentCount(int matchedPaymentCount) {
        this.matchedPaymentCount = matchedPaymentCount;
    }

    public int getQuarantinedRowCount() {
        return quarantinedRowCount;
    }

    public void setQuarantinedRowCount(int quarantinedRowCount) {
        this.quarantinedRowCount = quarantinedRowCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
