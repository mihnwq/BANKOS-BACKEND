package com.bankos.service;

import com.bankos.dto.CaseResponse;
import com.bankos.dto.CaseResponse.EvidenceGraph;
import com.bankos.dto.CaseSummary;
import com.bankos.dto.ReviewRequest;
import com.bankos.dto.ReviewResponse;
import com.bankos.model.AuditEvent;
import com.bankos.model.BankingCase;
import com.bankos.model.CaseStatus;
import com.bankos.model.CaseType;
import com.bankos.model.Evidence;
import com.bankos.model.ReviewStatus;
import com.bankos.repository.AgentExecutionRepository;
import com.bankos.repository.AuditEventRepository;
import com.bankos.repository.CaseRepository;
import com.bankos.repository.EvidenceRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Case listing, evidence-backed case detail and human review. */
@Service
public class CaseService {

    private final CaseRepository caseRepository;
    private final EvidenceRepository evidenceRepository;
    private final AgentExecutionRepository agentExecutionRepository;
    private final AuditEventRepository auditEventRepository;
    private final AuditService auditService;

    public CaseService(CaseRepository caseRepository, EvidenceRepository evidenceRepository,
                       AgentExecutionRepository agentExecutionRepository, AuditEventRepository auditEventRepository,
                       AuditService auditService) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.agentExecutionRepository = agentExecutionRepository;
        this.auditEventRepository = auditEventRepository;
        this.auditService = auditService;
    }

    public List<CaseSummary> list(String batchId, CaseStatus status, CaseType type) {
        List<BankingCase> cases = batchId == null ? caseRepository.findAllByOrderByIdAsc()
                : caseRepository.findByBatchIdOrderByIdAsc(batchId);
        return cases.stream()
                .filter(c -> status == null || c.getStatus() == status)
                .filter(c -> type == null || c.getType() == type)
                .map(CaseSummary::from)
                .toList();
    }

    public BankingCase find(String caseId) {
        return caseRepository.findById(caseId).orElseThrow(() -> ApiException.notFound("Case", caseId));
    }

    public CaseResponse detail(String caseId) {
        return toResponse(find(caseId));
    }

    public CaseResponse toResponse(BankingCase c) {
        List<Evidence> evidence = evidenceRepository.findByCaseIdOrderByIdAsc(c.getId());
        return new CaseResponse(
                c.getId(), c.getBatchId(), c.getType(), c.getSubjectId(), c.getPaymentId(), c.getSettlementIdList(),
                c.getReference(), c.getInvoiceNumber(), c.getExpectedAmount(), c.getActualAmount(), c.getDifference(),
                c.getCurrency(), c.getDescription(), c.getStatus(), c.getPolicyVersionId(), c.getAppliedRuleList(),
                c.getDecisionReason(),
                evidence.stream().map(e -> new CaseResponse.EvidenceItem(e.getId(), e.getSource(), e.getRowNumber(),
                        e.getPageNumber(), e.getField(), e.getRecordId(), e.getValue())).toList(),
                new CaseResponse.Investigation(c.getInvestigationStatus(), c.getInvestigationSummary(),
                        c.getPossibleCause(), c.getInvestigationEvidenceIdList(), c.getRequiresHumanReview(),
                        c.getInvestigationError(), c.getInvestigatedAt()),
                new CaseResponse.Review(c.getReviewStatus(), c.getReviewedBy(), c.getReviewedAt()),
                agentExecutionRepository.findByCaseIdOrderByIdAsc(c.getId()).stream()
                        .map(e -> new CaseResponse.TraceItem(e.getId(), e.getAgent(), e.getTool(), e.getState().name(),
                                e.getDurationMs(), e.getStartedAt(), e.getDetail(), e.getError()))
                        .toList(),
                auditEventRepository.findByCaseIdOrderByIdAsc(c.getId()).stream()
                        .map(a -> new CaseResponse.AuditItem(a.getId(), a.getAction(), a.getActor(), a.getDetails(),
                                a.getCreatedAt()))
                        .toList(),
                graph(c, evidence));
    }

    /**
     * Records a human decision as a separate, append-only audit event. Detection facts, the policy
     * decision and the investigation are left untouched; no money is moved.
     */
    public ReviewResponse review(String caseId, ReviewRequest request) {
        BankingCase c = find(caseId);
        ReviewStatus newStatus = switch (request.decision()) {
            case APPROVE -> ReviewStatus.APPROVED;
            case REJECT -> ReviewStatus.REJECTED;
            case ESCALATE -> ReviewStatus.ESCALATED;
            case INVESTIGATING -> ReviewStatus.INVESTIGATING;
        };
        ReviewStatus previous = c.getReviewStatus();
        String actor = request.actor().trim();
        String note = request.note() == null ? null : request.note().trim();
        AuditEvent event = auditService.record(c.getBatchId(), c.getId(), "REVIEW_" + request.decision(), actor,
                previous + " -> " + newStatus + " (policy status " + c.getStatus() + ", policy v" + c.getPolicyVersionId()
                        + ")" + (note == null || note.isEmpty() ? "" : ": " + note));
        c.setReviewStatus(newStatus);
        c.setReviewedBy(actor);
        c.setReviewedAt(Instant.now());
        caseRepository.save(c);
        return new ReviewResponse(event.getId(), c.getId(), newStatus, actor, note, event.getCreatedAt());
    }

    /** Payment - settlement - invoice - rule relationship graph built from real evidence IDs. */
    private EvidenceGraph graph(BankingCase c, List<Evidence> evidence) {
        Map<String, EvidenceGraph.Node> nodes = new LinkedHashMap<>();
        List<EvidenceGraph.Edge> edges = new ArrayList<>();
        String caseNode = "case:" + c.getId();
        nodes.put(caseNode, new EvidenceGraph.Node(caseNode, "case", c.getType().name(), c.getStatus().name()));
        for (Evidence e : evidence) {
            String kind = e.getPageNumber() != null ? "invoice"
                    : e.getSource().toLowerCase().contains("settlement") ? "settlement" : "payment";
            String nodeId = kind + ":" + e.getRecordId();
            nodes.putIfAbsent(nodeId, new EvidenceGraph.Node(nodeId, kind, e.getRecordId(),
                    e.getSource() + (e.getRowNumber() != null ? " row " + e.getRowNumber() : "")
                            + (e.getPageNumber() != null ? " page " + e.getPageNumber() : "")));
            edges.add(new EvidenceGraph.Edge(e.getId(), nodeId, caseNode, e.getField()));
        }
        for (String rule : c.getAppliedRuleList()) {
            String nodeId = "rule:" + rule;
            nodes.putIfAbsent(nodeId, new EvidenceGraph.Node(nodeId, "rule", rule,
                    "policy v" + c.getPolicyVersionId()));
            edges.add(new EvidenceGraph.Edge("decision:" + rule, caseNode, nodeId, c.getStatus().name()));
        }
        return new EvidenceGraph(List.copyOf(nodes.values()), edges);
    }
}
