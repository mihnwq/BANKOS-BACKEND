package com.bankos.dto;

import com.bankos.model.AgentExecution;
import com.bankos.model.AuditEvent;

import java.time.Instant;

/** One entry of the batch timeline: an agent stage/tool execution or an audit event. */
public record BatchEvent(
        String kind,
        Instant timestamp,
        String caseId,
        String agent,
        String tool,
        String state,
        Long durationMs,
        String actor,
        String action,
        String detail,
        String error
) {

    public static BatchEvent from(AgentExecution e) {
        return new BatchEvent("AGENT", e.getStartedAt(), e.getCaseId(), e.getAgent(), e.getTool(),
                e.getState().name(), e.getDurationMs(), null, null, e.getDetail(), e.getError());
    }

    public static BatchEvent from(AuditEvent e) {
        return new BatchEvent("AUDIT", e.getCreatedAt(), e.getCaseId(), null, null, null, null,
                e.getActor(), e.getAction(), e.getDetails(), null);
    }
}
