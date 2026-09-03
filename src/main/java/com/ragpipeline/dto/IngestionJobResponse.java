package com.ragpipeline.dto;

import com.ragpipeline.model.IngestionJobRecord;

import java.time.Instant;
import java.util.UUID;

/** Public ingestion-job status representation. */
public record IngestionJobResponse(
        UUID id,
        UUID documentId,
        String status,
        String errorMessage,
        Integer attempts,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt) {

    public static IngestionJobResponse from(IngestionJobRecord job) {
        return new IngestionJobResponse(
                job.getId(),
                job.getDocumentId(),
                job.getStatus(),
                job.getErrorMessage(),
                job.getAttempts(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getCreatedAt());
    }
}
