package com.bankos.repository;

import com.bankos.model.Batch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BatchRepository extends JpaRepository<Batch, String> {

    List<Batch> findAllByOrderByCreatedAtDesc();
}
