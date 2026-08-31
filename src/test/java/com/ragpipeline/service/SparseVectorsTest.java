package com.ragpipeline.service;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SparseVectorsTest {
    @Test
    void convertsSortedNonZeroEntries() {
        Map<Integer, Float> sparse = new LinkedHashMap<>();
        sparse.put(42, 0.8f);
        sparse.put(1, 0.22f);
        sparse.put(7, 0f);
        SparseVectors converted = SparseVectors.from(sparse);
        assertEquals(List.of(1, 42), converted.indices());
        assertEquals(List.of(0.22f, 0.8f), converted.values());
        assertFalse(converted.isEmpty());
    }

    @Test
    void treatsEmptyMapAsEmptySparseVector() {
        assertTrue(SparseVectors.from(Map.of()).isEmpty());
    }
}
