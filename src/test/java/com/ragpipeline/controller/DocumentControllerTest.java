package com.ragpipeline.controller;

import com.ragpipeline.dto.DocumentResponse;
import com.ragpipeline.model.Document;
import com.ragpipeline.model.IngestionJobRecord;
import com.ragpipeline.repository.DocumentRepository;
import com.ragpipeline.repository.IngestionJobRepository;
import com.ragpipeline.service.DocumentIngestionService;
import com.ragpipeline.service.EmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentControllerTest {
    private final DocumentIngestionService ingestion = mock(DocumentIngestionService.class);
    private final EmbeddingService embedding = mock(EmbeddingService.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final IngestionJobRepository jobs = mock(IngestionJobRepository.class);

    @Test
    void ingestAndReadEndpointsReturnDtosInsteadOfEntities() {
        UUID id = UUID.randomUUID();
        Document document = document(id);
        when(ingestion.ingest(any(), any(), any(), any(), any(), any())).thenReturn(document);
        when(documents.findAll()).thenReturn(List.of(document));
        when(documents.findById(id)).thenReturn(Optional.of(document));
        IngestionJobRecord job = IngestionJobRecord.builder().id(UUID.randomUUID()).documentId(id).status("INDEXED").attempts(1).build();
        when(jobs.findByDocumentIdOrderByCreatedAtDesc(id)).thenReturn(List.of(job));
        DocumentController controller = controller();

        var upload = controller.ingest(new MockMultipartFile("file", "policy.txt", "text/plain", "text".getBytes()),
                "RECURSIVE", "TechCorp", "AUTHORITATIVE", "POLICY", true);
        List<DocumentResponse> listed = controller.list();

        assertEquals(202, upload.getStatusCode().value());
        assertEquals(id, upload.getBody().id());
        assertEquals(List.of(id), listed.stream().map(DocumentResponse::id).toList());
        assertEquals(id, controller.get(id).getBody().id());
        assertEquals("INDEXED", controller.jobs(id).getFirst().status());
    }

    @Test
    void missingDocumentReturns404AndReindexReturnsCount() {
        UUID id = UUID.randomUUID();
        when(documents.findById(id)).thenReturn(Optional.empty());
        when(embedding.reindexAll()).thenReturn(7);

        assertEquals(404, controller().get(id).getStatusCode().value());
        assertEquals(7, controller().reindex().get("reindexedChunks"));
    }

    private DocumentController controller() { return new DocumentController(ingestion, embedding, documents, jobs); }
    private Document document(UUID id) { return Document.builder().id(id).fileName("policy.txt").fileType("text/plain").status("INDEXED").chunkingStrategy("RECURSIVE").build(); }
}
