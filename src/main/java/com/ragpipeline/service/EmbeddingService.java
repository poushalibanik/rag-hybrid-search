package com.ragpipeline.service;

import com.ragpipeline.model.BgeEmbedding;
import com.ragpipeline.model.Chunk;
import com.ragpipeline.repository.ChunkRepository;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.SparseVectorConfig;
import io.qdrant.client.grpc.Collections.SparseVectorParams;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorParamsMap;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.Points.NamedVectors;
import io.qdrant.client.grpc.Points.PointId;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.Vectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static io.qdrant.client.VectorFactory.vector;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {
    private final BgeM3EmbeddingService bge;
    private final QdrantClient qdrant;
    private final ChunkRepository repository;
    @Value("${qdrant.collection-name}") private String collection;
    @Value("${qdrant.dense-dimension:1024}") private int dimension;
    private volatile boolean hybridReady;

    public void embedAndStore(List<Chunk> chunks) {
        ensureHybridCollection();
        upsert(chunks);
    }

    /** Refreshes existing points so changed document metadata is reflected in Qdrant payload filters. */
    public int reindexAll() {
        ensureHybridCollection();
        List<Chunk> existing = repository.findIndexable();
        upsert(existing);
        return existing.size();
    }

    public void ensureHybridCollection() {
        if (hybridReady) return;
        synchronized (this) {
            if (hybridReady) return;
            try {
                boolean exists = qdrant.collectionExistsAsync(collection).get();
                boolean rebuilt = false;
                if (exists && !hasSparseVector()) {
                    log.warn("Qdrant collection '{}' is dense-only; recreating it with dense+sparse vectors and reindexing from PostgreSQL", collection);
                    qdrant.deleteCollectionAsync(collection).get();
                    exists = false;
                    rebuilt = true;
                }
                if (!exists) {
                    createHybridCollection();
                }
                if (rebuilt) {
                    List<Chunk> existing = repository.findIndexable();
                    if (!existing.isEmpty()) upsert(existing);
                }
                hybridReady = true;
            } catch (Exception error) {
                throw new IllegalStateException("Unable to create or access Qdrant collection '" + collection + "'", error);
            }
        }
    }

    private boolean hasSparseVector() throws Exception {
        CollectionInfo info = qdrant.getCollectionInfoAsync(collection).get();
        return info.hasConfig()
                && info.getConfig().hasParams()
                && info.getConfig().getParams().hasSparseVectorsConfig()
                && info.getConfig().getParams().getSparseVectorsConfig().containsMap("sparse");
    }

    private void createHybridCollection() throws Exception {
        qdrant.createCollectionAsync(CreateCollection.newBuilder()
                .setCollectionName(collection)
                .setVectorsConfig(VectorsConfig.newBuilder()
                        .setParamsMap(VectorParamsMap.newBuilder()
                                .putMap("dense", VectorParams.newBuilder()
                                        .setSize(dimension)
                                        .setDistance(Distance.Cosine)
                                        .build())
                                .build())
                        .build())
                .setSparseVectorsConfig(SparseVectorConfig.newBuilder()
                        .putMap("sparse", SparseVectorParams.getDefaultInstance())
                        .build())
                .build()).get();
    }

    private void upsert(List<Chunk> chunks) {
        for (int start = 0; start < chunks.size(); start += 8) {
            upsertBatch(chunks.subList(start, Math.min(start + 8, chunks.size())));
        }
    }

    private void upsertBatch(List<Chunk> batch) {
        List<BgeEmbedding> embeddings = bge.embedBatch(batch.stream().map(Chunk::getContent).toList());
        List<PointStruct> points = new ArrayList<>();
        for (int index = 0; index < batch.size(); index++) {
            Chunk chunk = batch.get(index);
            if (Boolean.TRUE.equals(chunk.getIsDuplicate())) continue;
            String pointId = chunk.getQdrantPointId() == null || chunk.getQdrantPointId().isBlank()
                    ? UUID.randomUUID().toString()
                    : chunk.getQdrantPointId();
            chunk.setQdrantPointId(pointId);
            BgeEmbedding embedding = embeddings.get(index);
            SparseVectors sparse = SparseVectors.from(embedding.getSparseVector());
            NamedVectors.Builder named = NamedVectors.newBuilder()
                    .putVectors("dense", vector(floats(embedding.getDenseVector())));
            if (!sparse.isEmpty()) {
                named.putVectors("sparse", vector(sparse.values(), sparse.indices()));
            }
            points.add(PointStruct.newBuilder()
                    .setId(PointId.newBuilder().setUuid(pointId))
                    .setVectors(Vectors.newBuilder().setVectors(named.build()).build())
                    .putAllPayload(Map.of(
                            "chunk_id", text(chunk.getId()),
                            "document_id", text(chunk.getDocument().getId()),
                            "file_name", text(chunk.getDocument().getFileName()),
                            "content", text(chunk.getContent()),
                            "section_heading", text(Objects.toString(chunk.getSectionHeading(), "")),
                            "chunk_index", text(chunk.getChunkIndex()),
                            "organization", text(Objects.toString(chunk.getDocument().getOrganization(), "Unknown")),
                            "authority", text(Objects.toString(chunk.getDocument().getAuthority(), "REFERENCE")),
                            "document_type", text(Objects.toString(chunk.getDocument().getDocumentType(), "REFERENCE")),
                            "is_current", bool(Boolean.TRUE.equals(chunk.getDocument().getCurrent()))))
                    .build());
        }
        try {
            if (!points.isEmpty()) qdrant.upsertAsync(collection, points).get();
            repository.saveAll(batch);
        } catch (Exception error) {
            throw new IllegalStateException("Qdrant upsert failed", error);
        }
    }

    private List<Float> floats(float[] vector) {
        List<Float> result = new ArrayList<>(vector.length);
        for (float value : vector) result.add(value);
        return result;
    }

    private io.qdrant.client.grpc.JsonWithInt.Value text(Object value) {
        return io.qdrant.client.grpc.JsonWithInt.Value.newBuilder().setStringValue(String.valueOf(value)).build();
    }

    private io.qdrant.client.grpc.JsonWithInt.Value bool(boolean value) {
        return io.qdrant.client.grpc.JsonWithInt.Value.newBuilder().setBoolValue(value).build();
    }
}
