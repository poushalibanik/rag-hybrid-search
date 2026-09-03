package com.ragpipeline.controller;

import com.ragpipeline.dto.DocumentResponse;
import com.ragpipeline.dto.IngestionJobResponse;
import com.ragpipeline.repository.DocumentRepository;
import com.ragpipeline.repository.IngestionJobRepository;
import com.ragpipeline.service.DocumentIngestionService;
import com.ragpipeline.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {
    private final DocumentIngestionService service;
    private final EmbeddingService embeddingService;
    private final DocumentRepository documents;
    private final IngestionJobRepository jobs;

    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> ingest(
            @RequestPart MultipartFile file,
            @RequestParam(required = false) String chunkingStrategy,
            @RequestParam(required = false) String organization,
            @RequestParam(required = false) String authority,
            @RequestParam(required = false) String documentType,
            @RequestParam(required = false) Boolean current) {
        return ResponseEntity.accepted().body(DocumentResponse.from(
                service.ingest(file, chunkingStrategy, organization, authority, documentType, current)));
    }

    @GetMapping
    public List<DocumentResponse> list() {
        return documents.findAll().stream().map(DocumentResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentResponse> get(@PathVariable UUID id) {
        return documents.findById(id)
                .map(DocumentResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/jobs")
    public List<IngestionJobResponse> jobs(@PathVariable UUID id) {
        return jobs.findByDocumentIdOrderByCreatedAtDesc(id).stream()
                .map(IngestionJobResponse::from)
                .toList();
    }

    @PostMapping("/reindex")
    public Map<String, Object> reindex() {
        return Map.of("reindexedChunks", embeddingService.reindexAll());
    }
}
