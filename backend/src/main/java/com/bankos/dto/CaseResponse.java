package com.bankos.dto;

import com.bankos.model.CaseStatus;
import com.bankos.model.CaseType;
import com.bankos.model.InvestigationStatus;
import com.bankos.model.ReviewStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Evidence-backed case detail returned by GET /api/cases/{id}. */
public record CaseResponse(
        String id,
        String batchId,
        CaseType type,
        String subjectId,
        String paymentId,
        List<String> settlementIds,
        String reference,
        String invoiceNumber,
        BigDecimal amount,
        BigDecimal actualAmount,
        BigDecimal difference,
        String currency,
        String description,
        CaseStatus status,
        Integer policyVersion,
        List<String> appliedRules,
        String decisionReason,
        List<EvidenceItem> evidence,
        Investigation investigation,
        Review review,
        List<TraceItem> agentTrace,
        List<AuditItem> auditTrail,
        EvidenceGraph graph
) {

    public record EvidenceItem(
            String id,
            String source,
            Integer row,
            Integer page,
            String field,
            String recordId,
            String value
    ) {}

    public record Investigation(
            InvestigationStatus status,
            String summary,
            String possibleCause,
            List<String> evidenceIds,
            Boolean requiresHumanReview,
            String error,
            Instant investigatedAt
    ) {}

    public record Review(
            ReviewStatus status,
            String reviewedBy,
            Instant reviewedAt
    ) {}

    public record TraceItem(
            Long id,
            String agent,
            String tool,
            String state,
            long durationMs,
            Instant startedAt,
            String detail,
            String error
    ) {}

    public record AuditItem(
            Long id,
            String action,
            String actor,
            String details,
            Instant createdAt
    ) {}

    /** Nodes and edges for the frontend evidence relationship graph (React Flow). */
    public record EvidenceGraph(List<Node> nodes, List<Edge> edges) {

        public record Node(String id, String kind, String label, String detail) {}

        public record Edge(String id, String source, String target, String label) {}
    }
}
