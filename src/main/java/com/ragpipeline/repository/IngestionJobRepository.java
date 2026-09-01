package com.ragpipeline.repository;

import com.ragpipeline.model.IngestionJobRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IngestionJobRepository extends JpaRepository<IngestionJobRecord, UUID> {
    List<IngestionJobRecord> findByDocumentIdOrderByCreatedAtDesc(UUID documentId);
}
