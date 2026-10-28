package com.bankos.service;

import com.bankos.model.AgentExecution;
import com.bankos.model.AuditEvent;
import com.bankos.model.ExecutionState;
import com.bankos.repository.AgentExecutionRepository;
import com.bankos.repository.AuditEventRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/** Append-only writer for audit events and agent executions (the agent trace). */
@Service
public class AuditService {

    public static final String SYSTEM_ACTOR = "system";

    private final AuditEventRepository auditEventRepository;
    private final AgentExecutionRepository agentExecutionRepository;

    public AuditService(AuditEventRepository auditEventRepository, AgentExecutionRepository agentExecutionRepository) {
        this.auditEventRepository = auditEventRepository;
        this.agentExecutionRepository = agentExecutionRepository;
    }

    public AuditEvent record(String batchId, String caseId, String action, String actor, String details) {
        return auditEventRepository.save(new AuditEvent(batchId, caseId, action, actor, details));
    }

    public AgentExecution execution(String batchId, String caseId, String agent, String tool, ExecutionState state,
                                    Instant startedAt, String detail, String error) {
        long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
        return agentExecutionRepository.save(
                new AgentExecution(batchId, caseId, agent, tool, state, startedAt, durationMs, detail, error));
    }
}
