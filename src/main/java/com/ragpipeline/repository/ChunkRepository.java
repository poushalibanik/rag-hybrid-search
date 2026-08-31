package com.ragpipeline.repository;

import com.ragpipeline.model.Chunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.UUID;

public interface ChunkRepository extends JpaRepository<Chunk, UUID> {
    List<Chunk> findByDocumentIdOrderByChunkIndex(UUID id);
    List<Chunk> findTop20ByOrderByCreatedAtDesc();

    @Query("select c from Chunk c join fetch c.document where c.isDuplicate = false or c.isDuplicate is null")
    List<Chunk> findIndexable();

    @Query("select c from Chunk c join fetch c.document where c.isDuplicate = false or c.isDuplicate is null")
    List<Chunk> findAllIndexableChunks();
}
