package com.bankos.agent;

import com.bankos.model.ExecutionState;
import com.bankos.service.AuditService;
import com.bankos.service.EvidenceService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Read-only evidence tools for the InvestigationAgent. Every invocation is recorded (tool, case,
 * duration, returned evidence IDs, success) so the multi-step agent behaviour is verifiable.
 * During an investigation a session scopes the tools to the case under investigation and enforces
 * the tool-call budget.
 */
@Component
public class InvestigationTools {

    public static final String AGENT = "InvestigationAgent";
    public static final String TOOL_EVIDENCE = "getCaseEvidence";
    public static final String TOOL_POLICY = "getCasePolicy";

    private static final ThreadLocal<Session> SESSION = new ThreadLocal<>();
    /** Fallback lookup in case the model client executes tools on another thread. */
    private static final Map<String, Session> ACTIVE = new ConcurrentHashMap<>();

    private final EvidenceService evidenceService;
    private final AuditService auditService;

    public InvestigationTools(EvidenceService evidenceService, AuditService auditService) {
        this.evidenceService = evidenceService;
        this.auditService = auditService;
    }

    @Tool(description = "Retrieve verified transaction and "
            + "invoice evidence for a case")
    public String getCaseEvidence(
            @ToolParam(description = "Case identifier")
            String caseId) {
        return invoke(TOOL_EVIDENCE, caseId, () -> evidenceService.getEvidenceJson(caseId));
    }

    @Tool(description = "Retrieve the applicable banking "
            + "policy for a case")
    public String getCasePolicy(
            @ToolParam(description = "Case identifier")
            String caseId) {
        return invoke(TOOL_POLICY, caseId, () -> evidenceService.getPolicyJson(caseId));
    }

    private String invoke(String tool, String caseId, Supplier<String> body) {
        Instant started = Instant.now();
        Session session = SESSION.get();
        if (session == null && caseId != null) {
            session = ACTIVE.get(caseId.trim());
        }
        String batchId = session == null ? null : session.batchId;
        if (session != null) {
            if (caseId == null || !session.caseId.equals(caseId.trim())) {
                auditService.execution(batchId, session.caseId, AGENT, tool, ExecutionState.FAILED, started,
                        "requested case " + caseId, "Out of scope: tools may only read the case under investigation");
                return "{\"error\":\"OUT_OF_SCOPE\",\"message\":\"Only case " + session.caseId + " may be accessed.\"}";
            }
            if (session.calls >= session.maxCalls) {
                auditService.execution(batchId, caseId, AGENT, tool, ExecutionState.FAILED, started,
                        null, "Tool-call budget of " + session.maxCalls + " exhausted");
                return "{\"error\":\"TOOL_BUDGET_EXCEEDED\",\"message\":\"Stop calling tools and answer with the evidence you have.\"}";
            }
            session.calls++;
            session.toolsUsed.add(tool);
        }
        try {
            String result = body.get();
            boolean failed = result.startsWith("{\"error\"");
            String detail = TOOL_EVIDENCE.equals(tool)
                    ? "returned evidence " + evidenceService.evidenceIds(caseId) : "returned policy for " + caseId;
            auditService.execution(batchId, caseId, AGENT, tool, failed ? ExecutionState.FAILED : ExecutionState.SUCCEEDED,
                    started, detail, failed ? result : null);
            return result;
        } catch (RuntimeException e) {
            auditService.execution(batchId, caseId, AGENT, tool, ExecutionState.FAILED, started, null, e.getMessage());
            return "{\"error\":\"TOOL_ERROR\",\"message\":\"Evidence could not be retrieved.\"}";
        }
    }

    /** Opens a scoped session on the current thread; always close it in a finally block. */
    public static Session openSession(String batchId, String caseId, int maxCalls) {
        Session session = new Session(batchId, caseId, maxCalls);
        SESSION.set(session);
        ACTIVE.put(caseId, session);
        return session;
    }

    public static void closeSession(Session session) {
        SESSION.remove();
        ACTIVE.remove(session.caseId, session);
    }

    public static final class Session {

        private final String batchId;
        private final String caseId;
        private final int maxCalls;
        private final Set<String> toolsUsed = Collections.synchronizedSet(new LinkedHashSet<>());
        private volatile int calls;

        private Session(String batchId, String caseId, int maxCalls) {
            this.batchId = batchId;
            this.caseId = caseId;
            this.maxCalls = maxCalls;
        }

        public Set<String> toolsUsed() {
            return Set.copyOf(toolsUsed);
        }

        public int calls() {
            return calls;
        }
    }
}
