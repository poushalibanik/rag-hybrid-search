package com.ragpipeline.service;

import com.ragpipeline.model.RetrievedChunk;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.ScoredPoint;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class HybridSearchMapper {
    private HybridSearchMapper() {}

    static Map<String, Double> scoresByPointId(List<ScoredPoint> points) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (ScoredPoint point : points) {
            scores.put(point.getId().getUuid(), (double) point.getScore());
        }
        return scores;
    }

    static RetrievedChunk toChunk(
            ScoredPoint point,
            Map<String, Double> denseScores,
            Map<String, Double> sparseScores,
            Map<String, Double> rrfScores) {
        String pointId = point.getId().getUuid();
        Map<String, Value> payload = point.getPayloadMap();
        return RetrievedChunk.builder()
                .chunkId(uuid(text(payload, "chunk_id")))
                .documentId(uuid(text(payload, "document_id")))
                .fileName(text(payload, "file_name"))
                .content(text(payload, "content"))
                .sectionHeading(blankToNull(text(payload, "section_heading")))
                .chunkIndex(parseInt(text(payload, "chunk_index")))
                .denseScore(denseScores.getOrDefault(pointId, 0d))
                .sparseScore(sparseScores.getOrDefault(pointId, 0d))
                .rrfScore(rrfScores.getOrDefault(pointId, 0d))
                .build();
    }

    private static String text(Map<String, Value> payload, String key) {
        Value value = payload.get(key);
        return value == null ? "" : value.getStringValue();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static UUID uuid(String value) {
        if (value == null || value.isBlank()) return null;
        return UUID.fromString(value);
    }

    private static int parseInt(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
