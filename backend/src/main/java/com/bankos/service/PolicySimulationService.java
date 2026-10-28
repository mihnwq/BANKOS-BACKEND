package com.bankos.service;

import com.bankos.agent.PolicyAgent;
import com.bankos.dto.Discrepancy;
import com.bankos.dto.PolicyDecision;
import com.bankos.dto.PolicyReplayRequest;
import com.bankos.dto.PolicyReplayResult;
import com.bankos.dto.PolicyResponse;
import com.bankos.model.BankingCase;
import com.bankos.model.Batch;
import com.bankos.model.CaseStatus;
import com.bankos.model.PolicyVersion;
import com.bankos.repository.CaseRepository;
import com.bankos.repository.PolicyRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Policy time machine: derives a new immutable policy version from proposed rule changes and
 * re-evaluates stored case facts against it. Stored decisions and earlier versions are never
 * modified; the result is a before/after comparison.
 */
@Service
public class PolicySimulationService {

    private final PolicyRepository policyRepository;
    private final CaseRepository caseRepository;
    private final BatchService batchService;
    private final PolicyAgent policyAgent;
    private final AuditService auditService;

    public PolicySimulationService(PolicyRepository policyRepository, CaseRepository caseRepository,
                                   BatchService batchService, PolicyAgent policyAgent, AuditService auditService) {
        this.policyRepository = policyRepository;
        this.caseRepository = caseRepository;
        this.batchService = batchService;
        this.policyAgent = policyAgent;
        this.auditService = auditService;
    }

    public List<PolicyResponse> list() {
        return policyRepository.findAllByOrderByIdAsc().stream().map(PolicyResponse::from).toList();
    }

    public PolicyVersion get(Integer version) {
        return policyRepository.findById(version)
                .orElseThrow(() -> ApiException.notFound("Policy version", String.valueOf(version)));
    }

    public synchronized PolicyReplayResult replay(PolicyReplayRequest request) {
        validateStatuses(request);
        List<BankingCase> cases;
        Integer defaultBase;
        if (request.batchId() != null) {
            Batch batch = batchService.getBatch(request.batchId());
            cases = caseRepository.findByBatchIdOrderByIdAsc(batch.getId());
            defaultBase = batch.getPolicyVersionId();
        } else {
            cases = caseRepository.findAllByOrderByIdAsc();
            defaultBase = null;
        }
        cases = cases.stream().filter(c -> c.getPolicyVersionId() != null).toList();

        Integer baseId = request.baseVersion() != null ? request.baseVersion()
                : defaultBase != null ? defaultBase
                : policyRepository.findAllByOrderByIdAsc().stream().findFirst().map(PolicyVersion::getId).orElseThrow();
        PolicyVersion base = get(baseId);
        PolicyVersion proposed = policyRepository.save(derive(base, request));
        auditService.record(request.batchId(), null, "POLICY_VERSION_CREATED", AuditService.SYSTEM_ACTOR,
                "v" + proposed.getId() + " derived from v" + base.getId() + ": " + proposed.getDescription());

        List<PolicyReplayResult.CaseChange> changes = new ArrayList<>();
        for (BankingCase c : cases) {
            PolicyDecision after = policyAgent.evaluate(Discrepancy.fromCase(c), proposed);
            if (after.status() != c.getStatus()) {
                changes.add(new PolicyReplayResult.CaseChange(c.getId(), c.getType(), c.getSubjectId(),
                        c.getDifference(), c.getCurrency(), c.getPolicyVersionId(), c.getStatus(),
                        c.getAppliedRuleList(), after.status(), after.appliedRules(), after.reason()));
            }
        }
        auditService.record(request.batchId(), null, "POLICY_REPLAY", AuditService.SYSTEM_ACTOR,
                "v" + proposed.getId() + " replayed over " + cases.size() + " stored cases; " + changes.size()
                        + " would change status " + changes.stream().map(PolicyReplayResult.CaseChange::caseId).toList()
                        + ". Stored decisions unchanged.");
        return new PolicyReplayResult(PolicyResponse.from(base), PolicyResponse.from(proposed), request.batchId(),
                cases.size(), changes.size(), changes, true);
    }

    private PolicyVersion derive(PolicyVersion base, PolicyReplayRequest r) {
        int nextId = policyRepository.findTopByOrderByIdDesc().map(PolicyVersion::getId).orElse(0) + 1;
        String description = r.description() != null && !r.description().isBlank() ? r.description().trim()
                : describeChanges(base, r);
        return new PolicyVersion(nextId, base.getCurrency(),
                r.amountTolerance() != null ? r.amountTolerance().setScale(2, java.math.RoundingMode.HALF_UP) : base.getTolerance(),
                r.requireInvoice() != null ? r.requireInvoice() : base.isRequireInvoice(),
                r.duplicatePaymentAllowed() != null ? r.duplicatePaymentAllowed() : base.isDuplicateAllowed(),
                r.missingSettlement() != null ? r.missingSettlement() : base.getMissingSettlement(),
                r.ambiguousReference() != null ? r.ambiguousReference() : base.getAmbiguousReference(),
                r.unexpectedSettlement() != null ? r.unexpectedSettlement() : base.getUnexpectedSettlement(),
                r.referenceMismatch() != null ? r.referenceMismatch() : base.getReferenceMismatch(),
                r.exportDuplicate() != null ? r.exportDuplicate() : base.getExportDuplicate(),
                base.getId(), description);
    }

    private static String describeChanges(PolicyVersion base, PolicyReplayRequest r) {
        List<String> parts = new ArrayList<>();
        if (r.amountTolerance() != null) {
            parts.add("amount_tolerance " + base.getTolerance().toPlainString() + " -> " + r.amountTolerance().toPlainString());
        }
        if (r.requireInvoice() != null) {
            parts.add("require_invoice " + base.isRequireInvoice() + " -> " + r.requireInvoice());
        }
        if (r.duplicatePaymentAllowed() != null) {
            parts.add("duplicate_payment_allowed " + base.isDuplicateAllowed() + " -> " + r.duplicatePaymentAllowed());
        }
        if (r.missingSettlement() != null) {
            parts.add("missing_settlement -> " + r.missingSettlement());
        }
        if (r.ambiguousReference() != null) {
            parts.add("ambiguous_reference -> " + r.ambiguousReference());
        }
        if (r.unexpectedSettlement() != null) {
            parts.add("unexpected_settlement -> " + r.unexpectedSettlement());
        }
        if (r.referenceMismatch() != null) {
            parts.add("reference_mismatch -> " + r.referenceMismatch());
        }
        if (r.exportDuplicate() != null) {
            parts.add("export_duplicate -> " + r.exportDuplicate());
        }
        return parts.isEmpty() ? "Replay of v" + base.getId() + " without changes" : String.join(", ", parts);
    }

    private static void validateStatuses(PolicyReplayRequest r) {
        boolean invalid = Stream.of(r.missingSettlement(), r.ambiguousReference(), r.unexpectedSettlement(),
                r.referenceMismatch(), r.exportDuplicate()).anyMatch(s -> s == CaseStatus.OPEN);
        if (invalid) {
            throw ApiException.badRequest("Rule outcomes must be AUTO_MATCHED or REVIEW_REQUIRED", List.of());
        }
    }
}
