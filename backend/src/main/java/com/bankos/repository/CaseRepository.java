package com.bankos.repository;

import com.bankos.model.BankingCase;
import com.bankos.model.CaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CaseRepository extends JpaRepository<BankingCase, String> {

    List<BankingCase> findByBatchIdOrderByIdAsc(String batchId);

    List<BankingCase> findByBatchIdAndStatusOrderByIdAsc(String batchId, CaseStatus status);

    List<BankingCase> findAllByOrderByIdAsc();

    long countByBatchId(String batchId);
}
