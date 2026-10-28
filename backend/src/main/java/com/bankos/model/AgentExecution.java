package com.bankos.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One recorded agent stage or tool invocation: which agent, which tool, for which case,
 * how long it took and whether it succeeded. This is the verifiable agent trace.
 */
@Entity
@Table(name = "agent_executions", indexes = {@Index(columnList = "batchId"), @Index(columnList = "caseId")})
public class AgentExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String batchId;
    private String caseId;

    @Column(nullable = false)
    private String agent;

    /** Tool or stage name, e.g. getCaseEvidence, RECONCILING. */
    private String tool;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionState state;

    @Column(nullable = false)
    private Instant startedAt;

    private long durationMs;

    @Column(length = 2000)
    private String detail;

    @Column(length = 2000)
    private String error;

    protected AgentExecution() {
    }

    public AgentExecution(String batchId, String caseId, String agent, String tool, ExecutionState state,
                          Instant startedAt, long durationMs, String detail, String error) {
        this.batchId = batchId;
        this.caseId = caseId;
        this.agent = agent;
        this.tool = tool;
        this.state = state;
        this.startedAt = startedAt;
        this.durationMs = durationMs;
        this.detail = truncate(detail);
        this.error = truncate(error);
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 2000 ? value : value.substring(0, 1997) + "...";
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

    public String getAgent() {
        return agent;
    }

    public String getTool() {
        return tool;
    }

    public ExecutionState getState() {
        return state;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getDetail() {
        return detail;
    }

    public String getError() {
        return error;
    }
}
