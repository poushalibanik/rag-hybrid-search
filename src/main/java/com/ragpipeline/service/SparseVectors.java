package com.ragpipeline.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

record SparseVectors(List<Integer> indices, List<Float> values) {
    static SparseVectors from(Map<Integer, Float> sparse) {
        List<Map.Entry<Integer, Float>> entries = new ArrayList<>(sparse.entrySet());
        entries.sort(Comparator.comparingInt(Map.Entry::getKey));
        List<Integer> indices = new ArrayList<>(entries.size());
        List<Float> values = new ArrayList<>(entries.size());
        for (Map.Entry<Integer, Float> entry : entries) {
            if (entry.getValue() == 0f) continue;
            indices.add(entry.getKey());
            values.add(entry.getValue());
        }
        return new SparseVectors(indices, values);
    }

    boolean isEmpty() {
        return indices.isEmpty();
    }
}
