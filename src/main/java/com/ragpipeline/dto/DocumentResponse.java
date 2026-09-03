package com.ragpipeline.dto;

import com.ragpipeline.model.Document;

import java.time.Instant;
import java.util.UUID;

/** Public document representation. Persistence-only fields, including raw content and hashes, stay internal. */
public record DocumentResponse(
        UUID id,
        String fileName,
        String sourcePath,
        String fileType,
        String status,
        String chunkingStrategy,
        String organization,
        String authority,
        String documentType,
        Boolean current,
        Instant createdAt,
        Instant updatedAt) {

    public static DocumentResponse from(Document document) {
        return new DocumentResponse(
                document.getId(),
                document.getFileName(),
                document.getSourcePath(),
                document.getFileType(),
                document.getStatus(),
                document.getChunkingStrategy(),
                document.getOrganization(),
                document.getAuthority(),
                document.getDocumentType(),
                document.getCurrent(),
                document.getCreatedAt(),
                document.getUpdatedAt());
    }
}
