package com.ragpipeline.service;

import com.google.common.util.concurrent.SettableFuture;
import com.ragpipeline.model.BgeEmbedding;
import com.ragpipeline.model.Chunk;
import com.ragpipeline.model.Document;
import com.ragpipeline.repository.ChunkRepository;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionOperationResponse;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Points.UpdateResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmbeddingServiceTest {
    @Test
    void createsHybridCollectionAndUpsertsNonDuplicateChunkPayload() {
        BgeM3EmbeddingService bge = mock(BgeM3EmbeddingService.class);
        QdrantClient qdrant = mock(QdrantClient.class);
        ChunkRepository repository = mock(ChunkRepository.class);
        EmbeddingService service = new EmbeddingService(bge, qdrant, repository);
        ReflectionTestUtils.setField(service, "collection", "test_chunks");
        ReflectionTestUtils.setField(service, "dimension", 3);
        when(qdrant.collectionExistsAsync("test_chunks")).thenReturn(done(false));
        when(qdrant.createCollectionAsync(any(CreateCollection.class))).thenReturn(done(CollectionOperationResponse.getDefaultInstance()));
        when(qdrant.upsertAsync(eq("test_chunks"), anyList())).thenReturn(done(UpdateResult.getDefaultInstance()));
        Document document = Document.builder().id(UUID.randomUUID()).fileName("policy.txt").fileType("text/plain")
                .organization("TechCorp").authority("AUTHORITATIVE").documentType("POLICY").current(true).build();
        Chunk chunk = Chunk.builder().id(UUID.randomUUID()).document(document).content("Refunds are allowed within 14 days.").chunkIndex(0).build();
        when(bge.embedBatch(List.of(chunk.getContent()))).thenReturn(List.of(BgeEmbedding.builder()
                .denseVector(new float[]{1f, 0f, 0f}).sparseVector(Map.of(12, 1f)).build()));

        service.embedAndStore(List.of(chunk));

        assertNotNull(chunk.getQdrantPointId());
        verify(qdrant).createCollectionAsync(any(CreateCollection.class));
        verify(qdrant).upsertAsync(eq("test_chunks"), argThat(points -> points.size() == 1
                && points.getFirst().getPayloadMap().get("authority").getStringValue().equals("AUTHORITATIVE")));
        verify(repository).saveAll(List.of(chunk));
    }

    @Test
    void reindexUsesStoredIndexableChunksAndSkipsDuplicatePoints() {
        BgeM3EmbeddingService bge = mock(BgeM3EmbeddingService.class);
        QdrantClient qdrant = mock(QdrantClient.class);
        ChunkRepository repository = mock(ChunkRepository.class);
        EmbeddingService service = new EmbeddingService(bge, qdrant, repository);
        ReflectionTestUtils.setField(service, "collection", "test_chunks");
        ReflectionTestUtils.setField(service, "hybridReady", true);
        when(qdrant.collectionExistsAsync("test_chunks")).thenReturn(done(true));
        Document document = Document.builder().id(UUID.randomUUID()).fileName("policy.txt").fileType("text/plain").build();
        Chunk duplicate = Chunk.builder().id(UUID.randomUUID()).document(document).content("duplicate").chunkIndex(0).isDuplicate(true).build();
        when(repository.findIndexable()).thenReturn(List.of(duplicate));
        when(bge.embedBatch(List.of("duplicate"))).thenReturn(List.of(BgeEmbedding.builder().denseVector(new float[]{1f}).sparseVector(Map.of()).build()));

        assertEquals(1, service.reindexAll());
        verify(qdrant, never()).upsertAsync(anyString(), anyList());
        verify(repository).saveAll(List.of(duplicate));
    }

    private static <T> SettableFuture<T> done(T value) {
        SettableFuture<T> future = SettableFuture.create();
        future.set(value);
        return future;
    }
}
