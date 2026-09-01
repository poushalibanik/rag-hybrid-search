package com.ragpipeline.repository;

import com.ragpipeline.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {
    Optional<Document> findByContentHash(String contentHash);
    Optional<Document> findFirstByRawContent(String rawContent);
}
