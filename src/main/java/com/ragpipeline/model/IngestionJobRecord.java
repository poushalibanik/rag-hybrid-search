package com.ragpipeline.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** Durable audit record for one asynchronous ingestion request. */
@Entity
@Table(name = "ingestion_jobs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class IngestionJobRecord {
    @Id @GeneratedValue private UUID id;
    @Column(nullable = false) private UUID documentId;
    @Column(nullable = false) private String status;
    @Column(columnDefinition = "TEXT") private String errorMessage;
    private Integer attempts;
    private Instant startedAt;
    private Instant completedAt;
    private Instant createdAt;

    @PrePersist void created() {
        if (status == null) status = "QUEUED";
        if (attempts == null) attempts = 0;
        if (createdAt == null) createdAt = Instant.now();
    }
}
