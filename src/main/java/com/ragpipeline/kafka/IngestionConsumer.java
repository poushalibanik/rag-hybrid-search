package com.ragpipeline.kafka;

import com.ragpipeline.model.Document;
import com.ragpipeline.model.IngestionJob;
import com.ragpipeline.repository.ChunkRepository;
import com.ragpipeline.repository.DocumentRepository;
import com.ragpipeline.service.ChunkingService;
import com.ragpipeline.service.DocumentIngestionService;
import com.ragpipeline.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IngestionConsumer {
    private final DocumentRepository documents;
    private final ChunkingService chunking;
    private final ChunkRepository chunks;
    private final DocumentIngestionService ingestion;
    private final EmbeddingService embedding;

    @RetryableTopic(attempts = "3", backoff = @Backoff(delay = 1000, multiplier = 2.0))
    @KafkaListener(topics = "${kafka.topics.ingestion-requests}")
    public void consume(IngestionJob job) {
        ingestion.markProcessing(job);
        Document document = documents.findById(job.getDocumentId()).orElseThrow();
        document.setStatus("PROCESSING");
        documents.save(document);
        var documentChunks = chunks.findByDocumentIdOrderByChunkIndex(document.getId());
        if (documentChunks.isEmpty()) documentChunks = chunks.saveAll(chunking.chunk(document, job.getChunkingStrategy()));
        // Upserts are safe. Retrying after a partial failure therefore completes vector indexing
        // instead of incorrectly marking an already-created set of chunks as fully indexed.
        embedding.embedAndStore(documentChunks);
        ingestion.markIndexed(job);
    }

    @DltHandler
    public void deadLetter(IngestionJob job, Exception error) {
        ingestion.markFailed(job, error);
    }
}
