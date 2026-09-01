package com.ragpipeline.controller;

import com.ragpipeline.model.Document;
import com.ragpipeline.model.IngestionJobRecord;
import com.ragpipeline.repository.DocumentRepository;
import com.ragpipeline.repository.IngestionJobRepository;
import com.ragpipeline.service.DocumentIngestionService;
import com.ragpipeline.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@RestController @RequestMapping("/api/v1/documents") @RequiredArgsConstructor
public class DocumentController {
    private final DocumentIngestionService service; private final EmbeddingService embeddingService;
    private final DocumentRepository documents; private final IngestionJobRepository jobs;
    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Document> ingest(@RequestPart MultipartFile file, @RequestParam(required = false) String chunkingStrategy,
            @RequestParam(required = false) String organization, @RequestParam(required = false) String authority,
            @RequestParam(required = false) String documentType, @RequestParam(required = false) Boolean current) {
        return ResponseEntity.accepted().body(service.ingest(file, chunkingStrategy, organization, authority, documentType, current));
    }
    @GetMapping public List<Document> list() { return documents.findAll(); }
    @GetMapping("/{id}") public ResponseEntity<Document> get(@PathVariable UUID id) { return documents.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build()); }
    @GetMapping("/{id}/jobs") public List<IngestionJobRecord> jobs(@PathVariable UUID id) { return jobs.findByDocumentIdOrderByCreatedAtDesc(id); }
    @PostMapping("/reindex") public Map<String, Object> reindex() { return Map.of("reindexedChunks", embeddingService.reindexAll()); }
}
