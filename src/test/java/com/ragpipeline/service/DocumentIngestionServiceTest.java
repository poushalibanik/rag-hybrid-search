package com.ragpipeline.service;

import com.ragpipeline.kafka.IngestionProducer;
import com.ragpipeline.model.Document;
import com.ragpipeline.model.IngestionJob;
import com.ragpipeline.model.IngestionJobRecord;
import com.ragpipeline.repository.DocumentRepository;
import com.ragpipeline.repository.IngestionJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionServiceTest {
    @Mock DocumentRepository documents;
    @Mock IngestionJobRepository jobs;
    @Mock IngestionProducer producer;

    @Test
    void duplicateContentReturnsExistingDocumentWithoutCreatingAnotherJob() {
        Document existing = Document.builder().id(UUID.randomUUID()).fileName("existing.txt")
                .fileType("text/plain").contentHash("existing").build();
        when(documents.findByContentHash(any())).thenReturn(Optional.of(existing));

        Document result = service().ingest(textFile("existing text"), "RECURSIVE", null, null, null, null);

        assertSame(existing, result);
        verify(documents, never()).save(any());
        verifyNoInteractions(jobs, producer);
    }

    @Test
    void newUploadPersistsMetadataAndPublishesOneJob() {
        UUID documentId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(documents.findByContentHash(any())).thenReturn(Optional.empty());
        when(documents.findFirstByRawContent(any())).thenReturn(Optional.empty());
        when(documents.save(any(Document.class))).thenAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            document.setId(documentId);
            return document;
        });
        when(jobs.save(any(IngestionJobRecord.class))).thenAnswer(invocation -> {
            IngestionJobRecord job = invocation.getArgument(0);
            job.setId(jobId);
            return job;
        });

        Document result = service().ingest(textFile("A new TechCorp policy."), "semantic",
                "TechCorp", "AUTHORITATIVE", "POLICY", true);

        assertEquals(documentId, result.getId());
        assertEquals("SEMANTIC", result.getChunkingStrategy());
        assertEquals("TechCorp", result.getOrganization());
        assertEquals("AUTHORITATIVE", result.getAuthority());
        assertEquals("POLICY", result.getDocumentType());
        assertTrue(result.getCurrent());
        assertNotNull(result.getContentHash());
        ArgumentCaptor<IngestionJob> jobCaptor = ArgumentCaptor.forClass(IngestionJob.class);
        verify(producer).send(jobCaptor.capture());
        assertEquals(jobId, jobCaptor.getValue().getJobId());
        assertEquals(documentId, jobCaptor.getValue().getDocumentId());
    }

    @Test
    void lifecycleMethodsPersistProcessingIndexedAndFailureStates() {
        UUID documentId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        IngestionJob message = IngestionJob.builder().documentId(documentId).jobId(jobId).build();
        Document document = Document.builder().id(documentId).fileName("test.txt").fileType("text/plain").build();
        IngestionJobRecord job = IngestionJobRecord.builder().id(jobId).documentId(documentId).status("QUEUED").attempts(0).build();
        when(documents.findById(documentId)).thenReturn(Optional.of(document));
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));

        DocumentIngestionService service = service();
        service.markProcessing(message);
        assertEquals("PROCESSING", job.getStatus());
        assertEquals(1, job.getAttempts());
        service.markIndexed(message);
        assertEquals("INDEXED", document.getStatus());
        assertEquals("INDEXED", job.getStatus());
        service.markFailed(message, new IllegalStateException("vector store unavailable"));
        assertEquals("FAILED", document.getStatus());
        assertEquals("FAILED", job.getStatus());
        assertEquals("vector store unavailable", job.getErrorMessage());
    }

    private DocumentIngestionService service() { return new DocumentIngestionService(documents, jobs, producer); }
    private MockMultipartFile textFile(String content) { return new MockMultipartFile("file", "policy.txt", "text/plain", content.getBytes()); }
}
