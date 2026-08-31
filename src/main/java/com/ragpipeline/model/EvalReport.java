package com.ragpipeline.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalReport {
    private String retrievalMode;
    private int totalCases;

    // Aggregate metrics (what goes in your README table)
    private double mrr5;                  // Mean Reciprocal Rank @5
    private double recall20;             // Recall @20
    private double avgCorrectness;       // Qwen3-as-judge
    private double avgFaithfulness;      // BGE reranker claim grounding
    private double avgCitationAccuracy;  // verified citations rate

    // Breakdowns
    private Map<String, Double> mrrByDifficulty;  // EASY/MEDIUM/HARD breakdown
    private List<String> retrievalFailures;        // cases where MRR@5 = 0
    private List<EvalCaseResult> caseResults;

    // Convenience: formatted summary for logging/printing
    public String summary() {
        return String.format(
            "\n=== EVAL REPORT: %s ===\n" +
            "  Cases:            %d\n" +
            "  MRR@5:            %.3f  (target: >0.70)\n" +
            "  Recall@20:        %.3f  (target: >0.85)\n" +
            "  Correctness:      %.3f  (target: >0.65)\n" +
            "  Faithfulness:     %.3f  (target: >0.75)\n" +
            "  Citation acc.:    %.3f  (target: >0.70)\n" +
            "  Failures (MRR=0): %d cases\n",
            retrievalMode, totalCases, mrr5, recall20,
            avgCorrectness, avgFaithfulness, avgCitationAccuracy,
            retrievalFailures != null ? retrievalFailures.size() : 0);
    }
}
