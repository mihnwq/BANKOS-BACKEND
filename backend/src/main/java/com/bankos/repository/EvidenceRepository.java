package com.bankos.repository;

import com.bankos.model.Evidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvidenceRepository extends JpaRepository<Evidence, String> {

    List<Evidence> findByCaseIdOrderByIdAsc(String caseId);
}
