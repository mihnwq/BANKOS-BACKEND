package com.bankos.repository;

import com.bankos.model.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findByBatchIdOrderByIdAsc(String batchId);

    List<AuditEvent> findByCaseIdOrderByIdAsc(String caseId);
}
