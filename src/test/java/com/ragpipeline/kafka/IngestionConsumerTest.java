package com.ragpipeline.kafka;

import com.ragpipeline.model.Chunk;
import com.ragpipeline.model.Document;
import com.ragpipeline.model.IngestionJob;
import com.ragpipeline.repository.ChunkRepository;
import com.ragpipeline.repository.DocumentRepository;
import com.ragpipeline.service.ChunkingService;
import com.ragpipeline.service.DocumentIngestionService;
import com.ragpipeline.service.EmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.RetryableTopic;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IngestionConsumerTest {
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final ChunkingService chunking = mock(ChunkingService.class);
    private final ChunkRepository chunks = mock(ChunkRepository.class);
    private final DocumentIngestionService ingestion = mock(DocumentIngestionService.class);
    private final EmbeddingService embedding = mock(EmbeddingService.class);

    @Test
    void newJobChunksEmbedsAndMarksIndexed() {
        IngestionJob job = job();
        Document document = document(job.getDocumentId());
        List<Chunk> created = List.of(Chunk.builder().content("chunk").build());
        when(documents.findById(job.getDocumentId())).thenReturn(Optional.of(document));
        when(chunks.findByDocumentIdOrderByChunkIndex(job.getDocumentId())).thenReturn(List.of());
        when(chunking.chunk(document, "RECURSIVE")).thenReturn(created);
        when(chunks.saveAll(created)).thenReturn(created);

        consumer().consume(job);

        verify(ingestion).markProcessing(job);
        verify(documents).save(document);
        verify(chunking).chunk(document, "RECURSIVE");
        verify(embedding).embedAndStore(created);
        verify(ingestion).markIndexed(job);
    }

    @Test
    void retryWithExistingChunksReembedsInsteadOfCreatingDuplicates() {
        IngestionJob job = job();
        Document document = document(job.getDocumentId());
        List<Chunk> existing = List.of(Chunk.builder().content("existing").build());
        when(documents.findById(job.getDocumentId())).thenReturn(Optional.of(document));
        when(chunks.findByDocumentIdOrderByChunkIndex(job.getDocumentId())).thenReturn(existing);

        consumer().consume(job);

        verify(chunking, never()).chunk(any(), anyString());
        verify(chunks, never()).saveAll(anyList());
        verify(embedding).embedAndStore(existing);
        verify(ingestion).markIndexed(job);
    }

    @Test
    void deadLetterMarksJobFailed() {
        IngestionJob job = job();
        IllegalStateException error = new IllegalStateException("Qdrant is unavailable");

        consumer().deadLetter(job, error);

        verify(ingestion).markFailed(job, error);
    }

    @Test
    void ingestionListenerIsConfiguredForThreeRetriesAndHasDeadLetterHandling() throws Exception {
        RetryableTopic retry = IngestionConsumer.class.getMethod("consume", IngestionJob.class)
                .getAnnotation(RetryableTopic.class);

        assertEquals("3", retry.attempts());
        assertNotNull(IngestionConsumer.class.getMethod("deadLetter", IngestionJob.class, Exception.class)
                .getAnnotation(DltHandler.class));
    }

    private IngestionConsumer consumer() { return new IngestionConsumer(documents, chunking, chunks, ingestion, embedding); }
    private IngestionJob job() { return IngestionJob.builder().jobId(UUID.randomUUID()).documentId(UUID.randomUUID()).chunkingStrategy("RECURSIVE").build(); }
    private Document document(UUID id) { return Document.builder().id(id).fileName("test.txt").fileType("text/plain").build(); }
}
