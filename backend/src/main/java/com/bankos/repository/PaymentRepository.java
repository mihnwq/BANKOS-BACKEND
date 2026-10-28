package com.bankos.repository;

import com.bankos.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByBatchIdOrderByRowNumberAsc(String batchId);

    List<Payment> findByBatchIdAndPaymentId(String batchId, String paymentId);

    long countByBatchId(String batchId);
}
