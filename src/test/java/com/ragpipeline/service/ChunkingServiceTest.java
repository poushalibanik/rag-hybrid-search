package com.ragpipeline.service;

import com.ragpipeline.model.Document;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkingServiceTest {
    @Test void createsOrderedChunks() {
        var service = new ChunkingService(null);
        service.size = 20; service.overlap = 2;
        var result = service.chunk(Document.builder().rawContent("One sentence. Two sentence. Three sentence.").chunkingStrategy("RECURSIVE").build());
        assertFalse(result.isEmpty());
        assertEquals(0, result.getFirst().getChunkIndex());
    }
}
