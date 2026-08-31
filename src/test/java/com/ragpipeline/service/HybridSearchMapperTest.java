package com.ragpipeline.service;

import com.ragpipeline.model.RetrievedChunk;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointId;
import io.qdrant.client.grpc.Points.ScoredPoint;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HybridSearchMapperTest {
    @Test
    void attachesDenseSparseAndRrfScoresByPointId() {
        UUID pointId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID chunkId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID documentId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        ScoredPoint ranked = ScoredPoint.newBuilder()
                .setId(PointId.newBuilder().setUuid(pointId.toString()))
                .setScore(0.031f)
                .putPayload("chunk_id", text(chunkId))
                .putPayload("document_id", text(documentId))
                .putPayload("file_name", text("policy.txt"))
                .putPayload("content", text("Refunds are issued within 14 days."))
                .putPayload("section_heading", text("Refunds"))
                .putPayload("chunk_index", text("2"))
                .build();
        RetrievedChunk chunk = HybridSearchMapper.toChunk(
                ranked,
                Map.of(pointId.toString(), 0.81),
                Map.of(pointId.toString(), 0.44),
                HybridSearchMapper.scoresByPointId(List.of(ranked)));
        assertEquals(chunkId, chunk.getChunkId());
        assertEquals(documentId, chunk.getDocumentId());
        assertEquals("policy.txt", chunk.getFileName());
        assertEquals(2, chunk.getChunkIndex());
        assertEquals(0.81, chunk.getDenseScore());
        assertEquals(0.44, chunk.getSparseScore());
        assertEquals(0.031, chunk.getRrfScore(), 1e-6);
    }

    private static Value text(Object value) {
        return Value.newBuilder().setStringValue(String.valueOf(value)).build();
    }
}
