package com.ragpipeline.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalCaseResult {
    private UUID evalCaseId;
    private String question;
    private String expectedAnswer;
    private String actualAnswer;
    private String sourceSection;
    private String difficulty;
    private String category;
    private String retrievalMode;

    // Retrieval metrics
    private double reciprocalRank;    // 0.0–1.0 (MRR@5 contribution)
    private boolean recallAt20;       // true if correct chunk in top-20

    // Answer quality metrics
    private double correctnessScore;  // 0.0–1.0 (Qwen3-as-judge)
    private double faithfulnessScore; // 0.0–1.0 (BGE reranker claim check)
    private double citationAccuracy;  // 0.0–1.0 (verified / total citations)

    // Debug info
    private List<String> top5ChunkIds;
    private List<String> correctChunkIds;
}
