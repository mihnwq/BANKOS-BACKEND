package com.bankos.repository;

import com.bankos.model.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvoiceRepository extends JpaRepository<Invoice, Long>
{

    List<Invoice> findByBatchIdOrderBySourceFileAsc(String batchId);

    long countByBatchId(String batchId);
}
