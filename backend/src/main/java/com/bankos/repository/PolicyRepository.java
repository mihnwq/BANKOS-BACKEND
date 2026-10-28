package com.bankos.repository;

import com.bankos.model.PolicyVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PolicyRepository extends JpaRepository<PolicyVersion, Integer> {

    List<PolicyVersion> findAllByOrderByIdAsc();

    Optional<PolicyVersion> findTopByOrderByIdDesc();
}
