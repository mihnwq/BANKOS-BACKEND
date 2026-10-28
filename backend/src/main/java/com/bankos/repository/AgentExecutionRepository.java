package com.bankos.repository;

import com.bankos.model.AgentExecution;
import com.bankos.model.ExecutionState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentExecutionRepository extends JpaRepository<AgentExecution, Long> {

    List<AgentExecution> findByBatchIdOrderByIdAsc(String batchId);

    List<AgentExecution> findByCaseIdOrderByIdAsc(String caseId);

    long countByBatchIdAndAgentAndToolNotNull(String batchId, String agent);

    long countByBatchIdAndState(String batchId, ExecutionState state);
}
