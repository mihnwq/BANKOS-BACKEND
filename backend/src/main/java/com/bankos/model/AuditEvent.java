package com.bankos.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** Append-only audit log entry: who did what, when. Never updated or deleted. */
@Entity
@Table(name = "audit_events", indexes = {@Index(columnList = "batchId"), @Index(columnList = "caseId")})
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String batchId;
    private String caseId;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false)
    private String actor;

    @Column(length = 4000)
    private String details;

    @Column(nullable = false)
    private Instant createdAt;

    protected AuditEvent() {
    }

    public AuditEvent(String batchId, String caseId, String action, String actor, String details) {
        this.batchId = batchId;
        this.caseId = caseId;
        this.action = action;
        this.actor = actor;
        this.details = details == null || details.length() <= 4000 ? details : details.substring(0, 3997) + "...";
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getBatchId() {
        return batchId;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getAction() {
        return action;
    }

    public String getActor() {
        return actor;
    }

    public String getDetails() {
        return details;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
