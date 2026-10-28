package com.bankos.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * An exception case. Detection facts (type, amounts, linked records) are written once by the
 * ReconciliationAgent and never overwritten; policy decisions, investigation results and
 * reviewer state live in their own fields and are additionally recorded as audit events.
 */
@Entity
@Table(name = "cases", indexes = @Index(columnList = "batchId"))
public class BankingCase {

    /** Stable, content-derived ID, e.g. CASE-B001-PAY-1042-DUP. */
    @Id
    private String id;

    @Column(nullable = false)
    private String batchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CaseType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CaseStatus status;

    // ---- detection facts (immutable after creation) ----
    /** Payment ID or, for settlement-only cases, settlement ID. */
    private String subjectId;
    private String paymentId;
    @Column(length = 1000)
    private String settlementIds;
    private String reference;
    private String invoiceNumber;
    @Column(precision = 19, scale = 2)
    private BigDecimal expectedAmount;
    @Column(precision = 19, scale = 2)
    private BigDecimal actualAmount;
    @Column(precision = 19, scale = 2)
    private BigDecimal difference;
    @Column(length = 3)
    private String currency;
    @Column(length = 2000)
    private String description;
    private boolean invoiceRequired;
    private boolean invoiceAvailable;

    // ---- policy decision ----
    private Integer policyVersionId;
    @Column(length = 500)
    private String appliedRules;
    @Column(length = 2000)
    private String decisionReason;
    private Instant decidedAt;

    // ---- investigation (advisory, untrusted until verified) ----
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvestigationStatus investigationStatus;
    @Column(length = 4000)
    private String investigationSummary;
    @Column(length = 4000)
    private String possibleCause;
    @Column(length = 2000)
    private String investigationEvidenceIds;
    private Boolean requiresHumanReview;
    @Column(length = 2000)
    private String investigationError;
    private Instant investigatedAt;

    // ---- human review ----
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewStatus reviewStatus;
    private String reviewedBy;
    private Instant reviewedAt;

    @Column(nullable = false)
    private Instant createdAt;

    protected BankingCase() {
    }

    public BankingCase(String id, String batchId, CaseType type) {
        this.id = id;
        this.batchId = batchId;
        this.type = type;
        this.status = CaseStatus.OPEN;
        this.investigationStatus = InvestigationStatus.NOT_RUN;
        this.reviewStatus = ReviewStatus.PENDING;
        this.createdAt = Instant.now();
    }

    /** True when the AI step failed or produced output that was rejected by verification. */
    public boolean isAiFailure() {
        return investigationStatus == InvestigationStatus.FAILED
                || investigationStatus == InvestigationStatus.REJECTED;
    }

    public List<String> getSettlementIdList() {
        return split(settlementIds);
    }

    public List<String> getInvestigationEvidenceIdList() {
        return split(investigationEvidenceIds);
    }

    public List<String> getAppliedRuleList() {
        return split(appliedRules);
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public String getId() {
        return id;
    }

    public String getBatchId() {
        return batchId;
    }

    public CaseType getType() {
        return type;
    }

    public CaseStatus getStatus() {
        return status;
    }

    public void setStatus(CaseStatus status) {
        this.status = status;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(String subjectId) {
        this.subjectId = subjectId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
    }

    public String getSettlementIds() {
        return settlementIds;
    }

    public void setSettlementIds(List<String> settlementIds) {
        this.settlementIds = settlementIds == null ? null : String.join(",", settlementIds);
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public void setInvoiceNumber(String invoiceNumber) {
        this.invoiceNumber = invoiceNumber;
    }

    public BigDecimal getExpectedAmount() {
        return expectedAmount;
    }

    public void setExpectedAmount(BigDecimal expectedAmount) {
        this.expectedAmount = expectedAmount;
    }

    public BigDecimal getActualAmount() {
        return actualAmount;
    }

    public void setActualAmount(BigDecimal actualAmount) {
        this.actualAmount = actualAmount;
    }

    public BigDecimal getDifference() {
        return difference;
    }

    public void setDifference(BigDecimal difference) {
        this.difference = difference;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isInvoiceRequired() {
        return invoiceRequired;
    }

    public void setInvoiceRequired(boolean invoiceRequired) {
        this.invoiceRequired = invoiceRequired;
    }

    public boolean isInvoiceAvailable() {
        return invoiceAvailable;
    }

    public void setInvoiceAvailable(boolean invoiceAvailable) {
        this.invoiceAvailable = invoiceAvailable;
    }

    public Integer getPolicyVersionId() {
        return policyVersionId;
    }

    public void setPolicyVersionId(Integer policyVersionId) {
        this.policyVersionId = policyVersionId;
    }

    public String getAppliedRules() {
        return appliedRules;
    }

    public void setAppliedRules(List<String> appliedRules) {
        this.appliedRules = appliedRules == null ? null : String.join(",", appliedRules);
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public void setDecisionReason(String decisionReason) {
        this.decisionReason = decisionReason;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public InvestigationStatus getInvestigationStatus() {
        return investigationStatus;
    }

    public void setInvestigationStatus(InvestigationStatus investigationStatus) {
        this.investigationStatus = investigationStatus;
    }

    public String getInvestigationSummary() {
        return investigationSummary;
    }

    public void setInvestigationSummary(String investigationSummary) {
        this.investigationSummary = investigationSummary;
    }

    public String getPossibleCause() {
        return possibleCause;
    }

    public void setPossibleCause(String possibleCause) {
        this.possibleCause = possibleCause;
    }

    public void setInvestigationEvidenceIds(List<String> evidenceIds) {
        this.investigationEvidenceIds = evidenceIds == null ? null : String.join(",", evidenceIds);
    }

    public Boolean getRequiresHumanReview() {
        return requiresHumanReview;
    }

    public void setRequiresHumanReview(Boolean requiresHumanReview) {
        this.requiresHumanReview = requiresHumanReview;
    }

    public String getInvestigationError() {
        return investigationError;
    }

    public void setInvestigationError(String investigationError) {
        this.investigationError = investigationError;
    }

    public Instant getInvestigatedAt() {
        return investigatedAt;
    }

    public void setInvestigatedAt(Instant investigatedAt) {
        this.investigatedAt = investigatedAt;
    }

    public ReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(ReviewStatus reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(String reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
