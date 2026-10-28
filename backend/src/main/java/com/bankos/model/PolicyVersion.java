package com.bankos.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An immutable, versioned set of fictional hackathon rules (not real banking regulation).
 * Replay creates a new version; existing versions are never modified.
 */
@Entity
@Table(name = "policy_versions")
public class PolicyVersion {

    /** The version number doubles as a stable ID. */
    @Id
    private Integer id;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal tolerance;

    private boolean requireInvoice;
    private boolean duplicateAllowed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CaseStatus missingSettlement;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CaseStatus ambiguousReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CaseStatus unexpectedSettlement;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CaseStatus referenceMismatch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CaseStatus exportDuplicate;

    /** Version this one was derived from (replay), null for the original. */
    private Integer basedOn;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private Instant createdAt;

    protected PolicyVersion() {
    }

    public PolicyVersion(Integer id, String currency, BigDecimal tolerance, boolean requireInvoice,
                         boolean duplicateAllowed, CaseStatus missingSettlement, CaseStatus ambiguousReference,
                         CaseStatus unexpectedSettlement, CaseStatus referenceMismatch, CaseStatus exportDuplicate,
                         Integer basedOn, String description) {
        this.id = id;
        this.currency = currency;
        this.tolerance = tolerance;
        this.requireInvoice = requireInvoice;
        this.duplicateAllowed = duplicateAllowed;
        this.missingSettlement = missingSettlement;
        this.ambiguousReference = ambiguousReference;
        this.unexpectedSettlement = unexpectedSettlement;
        this.referenceMismatch = referenceMismatch;
        this.exportDuplicate = exportDuplicate;
        this.basedOn = basedOn;
        this.description = description;
        this.createdAt = Instant.now();
    }

    /** Built-in defaults identical to synthetic-data/policies.json version 1. */
    public static PolicyVersion defaults() {
        return new PolicyVersion(1, "EUR", new BigDecimal("10.00"), true, false,
                CaseStatus.REVIEW_REQUIRED, CaseStatus.REVIEW_REQUIRED, CaseStatus.REVIEW_REQUIRED,
                CaseStatus.REVIEW_REQUIRED, CaseStatus.AUTO_MATCHED, null, "Built-in default policy");
    }

    public Integer getId() {
        return id;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getTolerance() {
        return tolerance;
    }

    public boolean isRequireInvoice() {
        return requireInvoice;
    }

    public boolean isDuplicateAllowed() {
        return duplicateAllowed;
    }

    public CaseStatus getMissingSettlement() {
        return missingSettlement;
    }

    public CaseStatus getAmbiguousReference() {
        return ambiguousReference;
    }

    public CaseStatus getUnexpectedSettlement() {
        return unexpectedSettlement;
    }

    public CaseStatus getReferenceMismatch() {
        return referenceMismatch;
    }

    public CaseStatus getExportDuplicate() {
        return exportDuplicate;
    }

    public Integer getBasedOn() {
        return basedOn;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
