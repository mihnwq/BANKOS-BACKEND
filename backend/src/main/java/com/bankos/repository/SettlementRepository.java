package com.bankos.repository;

import com.bankos.model.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SettlementRepository extends JpaRepository<Settlement, Long>
{

    List<Settlement> findByBatchIdOrderByRowNumberAsc(String batchId);

    List<Settlement> findByBatchIdAndSettlementIdIn(String batchId, Collection<String> settlementIds);

    List<Settlement> findByBatchIdAndPaymentId(String batchId, String paymentId);

    long countByBatchId(String batchId);
}
