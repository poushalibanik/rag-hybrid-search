package com.ragpipeline.service;

import com.google.common.util.concurrent.SettableFuture;
import com.ragpipeline.model.BgeEmbedding;
import com.ragpipeline.model.Chunk;
import com.ragpipeline.model.Document;
import com.ragpipeline.model.RetrievedChunk;
import com.ragpipeline.repository.ChunkRepository;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointId;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QdrantHybridRetrievalServiceTest {
    @Test
    void hybridSearchMapsQdrantPayloadReranksAndFiltersContext() {
        BgeM3EmbeddingService embeddings = mock(BgeM3EmbeddingService.class);
        BgeRerankerService reranker = mock(BgeRerankerService.class);
        ChunkRepository repository = mock(ChunkRepository.class);
        QdrantClient qdrant = mock(QdrantClient.class);
        EmbeddingService index = mock(EmbeddingService.class);
        QdrantHybridRetrievalService service = service(embeddings, reranker, repository, qdrant, index);
        when(embeddings.embed("What is the current TechCorp policy?")).thenReturn(BgeEmbedding.builder()
                .originalText("What is the current TechCorp policy?").denseVector(new float[]{1f, 0f}).sparseVector(Map.of(4, 1f)).build());
        when(qdrant.queryAsync(any(QueryPoints.class))).thenReturn(done(List.of(point())));
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> {
            List<RetrievedChunk> candidates = invocation.getArgument(1);
            candidates.forEach(chunk -> chunk.setRerankerScore(0.91));
            return candidates;
        });

        List<RetrievedChunk> retrieved = service.retrieve("What is the current TechCorp policy?", "HYBRID");

        assertEquals(1, retrieved.size());
        assertEquals("1.1 Refund Window", retrieved.getFirst().getSectionHeading());
        assertEquals(0.91, retrieved.getFirst().getRerankerScore());
        verify(index).ensureHybridCollection();
        verify(qdrant, times(3)).queryAsync(any(QueryPoints.class));
    }

    @Test
    void unscopedEmptySearchFallsBackToPostgresButScopedSearchDoesNot() {
        BgeM3EmbeddingService embeddings = mock(BgeM3EmbeddingService.class);
        BgeRerankerService reranker = mock(BgeRerankerService.class);
        ChunkRepository repository = mock(ChunkRepository.class);
        QdrantClient qdrant = mock(QdrantClient.class);
        EmbeddingService index = mock(EmbeddingService.class);
        QdrantHybridRetrievalService service = service(embeddings, reranker, repository, qdrant, index);
        when(embeddings.embed(anyString())).thenAnswer(invocation -> BgeEmbedding.builder().originalText(invocation.getArgument(0))
                .denseVector(new float[]{1f}).sparseVector(Map.of()).build());
        when(qdrant.queryAsync(any(QueryPoints.class))).thenReturn(done(List.of()));
        Document document = Document.builder().id(UUID.randomUUID()).fileName("fallback.txt").fileType("text/plain").build();
        Chunk stored = Chunk.builder().id(UUID.randomUUID()).document(document).content("Fallback answer").chunkIndex(0).build();
        when(repository.findTop20ByOrderByCreatedAtDesc()).thenReturn(List.of(stored));
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));

        assertEquals(1, service.retrieveTopK("What is the refund policy?", "HYBRID", 5).size());
        assertTrue(service.retrieveTopK("What is the current TechCorp policy?", "HYBRID", 5).isEmpty());
    }

    private QdrantHybridRetrievalService service(BgeM3EmbeddingService embeddings, BgeRerankerService reranker,
            ChunkRepository repository, QdrantClient qdrant, EmbeddingService index) {
        QdrantHybridRetrievalService service = new QdrantHybridRetrievalService(embeddings, reranker, repository, qdrant, index);
        ReflectionTestUtils.setField(service, "collection", "test_chunks");
        ReflectionTestUtils.setField(service, "top", 20);
        ReflectionTestUtils.setField(service, "finalTop", 5);
        ReflectionTestUtils.setField(service, "minimumGenerationScore", 0.30);
        ReflectionTestUtils.setField(service, "relativeGenerationScore", 0.35);
        ReflectionTestUtils.setField(service, "maximumContextChunks", 3);
        return service;
    }

    private static ScoredPoint point() {
        UUID pointId = UUID.randomUUID();
        return ScoredPoint.newBuilder().setId(PointId.newBuilder().setUuid(pointId.toString())).setScore(0.9f)
                .putPayload("chunk_id", text(UUID.randomUUID())).putPayload("document_id", text(UUID.randomUUID()))
                .putPayload("file_name", text("handbook.docx")).putPayload("content", text("Refunds are allowed within 14 days."))
                .putPayload("section_heading", text("1.1 Refund Window")).putPayload("chunk_index", text(0)).build();
    }

    private static Value text(Object value) { return Value.newBuilder().setStringValue(String.valueOf(value)).build(); }
    private static <T> SettableFuture<T> done(T value) { SettableFuture<T> future = SettableFuture.create(); future.set(value); return future; }
}
