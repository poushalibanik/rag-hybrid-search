package com.ragpipeline.service;

import com.ragpipeline.model.BgeEmbedding;
import com.ragpipeline.model.RetrievedChunk;
import com.ragpipeline.repository.ChunkRepository;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.Fusion;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.PrefetchQuery;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.qdrant.client.QueryFactory.fusion;
import static io.qdrant.client.QueryFactory.nearest;
import static io.qdrant.client.WithPayloadSelectorFactory.enable;
import static io.qdrant.client.ConditionFactory.match;
import static io.qdrant.client.ConditionFactory.matchKeyword;

@Slf4j
@Service
@RequiredArgsConstructor
public class QdrantHybridRetrievalService {
    private final BgeM3EmbeddingService embeddings;
    private final BgeRerankerService reranker;
    private final ChunkRepository chunks;
    private final QdrantClient qdrant;
    private final EmbeddingService index;
    @Value("${qdrant.collection-name}") String collection;
    @Value("${rag.retrieval.hybrid-top-k:20}") int top;
    @Value("${rag.retrieval.reranker-top-k:5}") int finalTop;
    @Value("${rag.generation.min-reranker-score:0.30}") double minimumGenerationScore;
    @Value("${rag.generation.relative-reranker-score:0.35}") double relativeGenerationScore;
    @Value("${rag.generation.max-context-chunks:3}") int maximumContextChunks;

    public List<RetrievedChunk> retrieve(String question, String mode) {
        List<RetrievedChunk> reranked = retrieveTopK(question, mode, top);
        if (reranked.isEmpty()) return reranked;

        double bestScore = reranked.getFirst().getRerankerScore();
        List<RetrievedChunk> selected = reranked.stream()
                .filter(chunk -> chunk.getRerankerScore() >= minimumGenerationScore)
                .filter(chunk -> chunk.getRerankerScore() >= bestScore * relativeGenerationScore)
                .limit(Math.min(finalTop, maximumContextChunks))
                .toList();

        // The best available chunk is still useful when every candidate is weak.
        if (selected.isEmpty()) {
            log.info("No chunk passed the generation relevance gate; retaining only the best candidate (score={})", bestScore);
            return List.of(reranked.getFirst());
        }
        log.debug("Selected {}/{} reranked chunks for generation; bestScore={}, minimumScore={}, relativeFloor={}",
                selected.size(), reranked.size(), bestScore, minimumGenerationScore, bestScore * relativeGenerationScore);
        return selected;
    }

    public List<RetrievedChunk> retrieveTopK(String question, String mode, int topK) {
        BgeEmbedding query = embeddings.embed(question);
        List<RetrievedChunk> candidates = search(query, mode == null ? "HYBRID" : mode);
        if (candidates.isEmpty()) {
            if (sourceScope(question) != null) {
                log.warn("No chunks satisfy the authority scope for this question; not using an unfiltered fallback");
                return List.of();
            }
            log.warn("Qdrant hybrid search returned no points; falling back to recent PostgreSQL chunks");
            candidates = fallback();
        }
        return reranker.rerank(question, candidates, topK);
    }

    private List<RetrievedChunk> search(BgeEmbedding query, String mode) {
        try {
            index.ensureHybridCollection();
            Filter scope = sourceScope(query == null ? "" : query.getOriginalText());
            List<Float> dense = floats(query.getDenseVector());
            SparseVectors sparse = SparseVectors.from(query.getSparseVector() == null ? Map.of() : query.getSparseVector());
            List<ScoredPoint> denseHits = queryPoints(denseQuery(dense, scope));
            List<ScoredPoint> sparseHits = sparse.isEmpty() ? List.of() : queryPoints(sparseQuery(sparse, scope));
            List<ScoredPoint> hybridHits = queryPoints(hybridQuery(dense, sparse, scope));
            Map<String, Double> denseScores = HybridSearchMapper.scoresByPointId(denseHits);
            Map<String, Double> sparseScores = HybridSearchMapper.scoresByPointId(sparseHits);
            Map<String, Double> rrfScores = HybridSearchMapper.scoresByPointId(hybridHits);
            List<ScoredPoint> ranked = switch (mode.toUpperCase(Locale.ROOT)) {
                case "DENSE", "DENSE_ONLY" -> denseHits;
                case "SPARSE", "SPARSE_ONLY" -> sparseHits;
                default -> hybridHits;
            };
            List<RetrievedChunk> results = new ArrayList<>(ranked.size());
            for (ScoredPoint point : ranked) {
                results.add(HybridSearchMapper.toChunk(point, denseScores, sparseScores, rrfScores));
            }
            return results;
        } catch (Exception error) {
            log.warn("Qdrant native hybrid search failed: {}", error.getMessage());
            return List.of();
        }
    }

    private List<ScoredPoint> queryPoints(QueryPoints request) throws Exception {
        return qdrant.queryAsync(request).get();
    }

    private QueryPoints denseQuery(List<Float> dense, Filter scope) {
        QueryPoints.Builder builder = QueryPoints.newBuilder()
                .setCollectionName(collection)
                .setQuery(nearest(dense))
                .setUsing("dense")
                .setLimit(top)
                .setWithPayload(enable(true));
        if (scope != null) builder.setFilter(scope);
        return builder.build();
    }

    private QueryPoints sparseQuery(SparseVectors sparse, Filter scope) {
        QueryPoints.Builder builder = QueryPoints.newBuilder()
                .setCollectionName(collection)
                .setQuery(nearest(sparse.values(), sparse.indices()))
                .setUsing("sparse")
                .setLimit(top)
                .setWithPayload(enable(true));
        if (scope != null) builder.setFilter(scope);
        return builder.build();
    }

    private QueryPoints hybridQuery(List<Float> dense, SparseVectors sparse, Filter scope) {
        QueryPoints.Builder builder = QueryPoints.newBuilder()
                .setCollectionName(collection)
                .addPrefetch(prefetch(nearest(dense), "dense", scope))
                .setQuery(fusion(Fusion.RRF))
                .setLimit(top)
                .setWithPayload(enable(true));
        if (!sparse.isEmpty()) builder.addPrefetch(prefetch(nearest(sparse.values(), sparse.indices()), "sparse", scope));
        return builder.build();
    }

    private PrefetchQuery prefetch(io.qdrant.client.grpc.Points.Query query, String vectorName, Filter scope) {
        PrefetchQuery.Builder builder = PrefetchQuery.newBuilder()
                .setQuery(query)
                .setUsing(vectorName)
                .setLimit(top);
        if (scope != null) builder.setFilter(scope);
        return builder.build();
    }

    /** Scopes explicit TechCorp questions to TechCorp; current-policy requests accept only current authoritative policy. */
    private Filter sourceScope(String question) {
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (!normalized.contains("techcorp")) return null;
        Filter.Builder filter = Filter.newBuilder().addMust(matchKeyword("organization", "TechCorp"));
        if (normalized.contains("current") || normalized.contains("policy")) {
            filter.addMust(matchKeyword("authority", "AUTHORITATIVE"));
            filter.addMust(match("is_current", true));
        }
        return filter.build();
    }

    private List<RetrievedChunk> fallback() {
        return chunks.findTop20ByOrderByCreatedAtDesc().stream()
                .map(chunk -> RetrievedChunk.builder()
                        .chunkId(chunk.getId())
                        .documentId(chunk.getDocument().getId())
                        .fileName(chunk.getDocument().getFileName())
                        .content(chunk.getContent())
                        .sectionHeading(chunk.getSectionHeading())
                        .chunkIndex(chunk.getChunkIndex())
                        .build())
                .toList();
    }

    private List<Float> floats(float[] vector) {
        List<Float> result = new ArrayList<>(vector.length);
        for (float value : vector) result.add(value);
        return result;
    }
}
