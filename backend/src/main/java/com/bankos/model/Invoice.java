package com.bankos.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** Structured invoice facts extracted from a supporting PDF, with provenance. */
@Entity
@Table(name = "invoices", indexes = @Index(columnList = "batchId"))
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String batchId;

    private String number;
    private String supplier;

    @Column(precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(length = 3)
    private String currency;

    private String paymentReference;

    @Column(nullable = false)
    private String sourceFile;

    /** Page number supplied by PdfService, never by the model. */
    private Integer sourcePage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceStatus status;

    /** AI, OFFLINE_PARSER or FALLBACK_PARSER. */
    private String extractionMethod;

    @Column(length = 2000)
    private String verificationNotes;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String pageText;

    protected Invoice() {
    }

    public Invoice(String batchId, String sourceFile) {
        this.batchId = batchId;
        this.sourceFile = sourceFile;
        this.status = InvoiceStatus.FAILED;
    }

    public boolean isUsableEvidence() {
        return status == InvoiceStatus.VERIFIED;
    }

    public Long getId() {
        return id;
    }

    public String getBatchId() {
        return batchId;
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public String getSupplier() {
        return supplier;
    }

    public void setSupplier(String supplier) {
        this.supplier = supplier;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public void setPaymentReference(String paymentReference) {
        this.paymentReference = paymentReference;
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public Integer getSourcePage() {
        return sourcePage;
    }

    public void setSourcePage(Integer sourcePage) {
        this.sourcePage = sourcePage;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public void setStatus(InvoiceStatus status) {
        this.status = status;
    }

    public String getExtractionMethod() {
        return extractionMethod;
    }

    public void setExtractionMethod(String extractionMethod) {
        this.extractionMethod = extractionMethod;
    }

    public String getVerificationNotes() {
        return verificationNotes;
    }

    public void setVerificationNotes(String verificationNotes) {
        this.verificationNotes = verificationNotes;
    }

    public String getPageText() {
        return pageText;
    }

    public void setPageText(String pageText) {
        this.pageText = pageText;
    }
}
