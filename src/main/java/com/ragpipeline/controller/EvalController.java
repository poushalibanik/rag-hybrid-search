package com.ragpipeline.controller;

import com.ragpipeline.model.EvalReport;
import com.ragpipeline.service.EvalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * EVAL CONTROLLER — all endpoints you need to measure and improve your system
 *
 * HOW TO USE EACH ENDPOINT:
 *
 * 1. Single mode:
 *    POST /api/v1/eval/run?retrievalMode=HYBRID
 *    → returns EvalReport with all 5 metrics + per-case breakdown
 *
 * 2. Compare all 3 modes side by side:
 *    POST /api/v1/eval/run/compare
 *    → returns Map<mode, EvalReport> — this is your README table
 *
 * 3. Retrieval-only (no LLM generation — much faster):
 *    POST /api/v1/eval/retrieval?retrievalMode=HYBRID
 *    → returns only MRR@5 and Recall@20 — use this when tuning chunking
 *       because you don't need to wait for Qwen3 generation for every case
 *
 * 4. Single question debug:
 *    POST /api/v1/eval/debug
 *    Body: {"question": "...", "expectedSection": "1.1 Standard Refund Window", "retrievalMode": "HYBRID"}
 *    → returns the top-5 chunks retrieved, whether the correct one was found,
 *      its rank, and the generated answer — useful when a specific case is failing
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/eval")
@RequiredArgsConstructor
public class EvalController {

    private final EvalService evalService;

    @PostMapping("/run")
    public ResponseEntity<EvalReport> run(
            @RequestParam(defaultValue = "HYBRID") String retrievalMode) {
        log.info("Starting eval run: mode={}", retrievalMode);
        EvalReport report = evalService.runEval(retrievalMode);
        log.info(report.summary());
        return ResponseEntity.ok(report);
    }

    @PostMapping("/run/compare")
    public ResponseEntity<Map<String, EvalReport>> compare() {
        log.info("Starting comparison eval: HYBRID vs DENSE_ONLY vs SPARSE_ONLY");
        Map<String, EvalReport> results = evalService.runComparison();
        results.forEach((mode, report) -> log.info(report.summary()));
        return ResponseEntity.ok(results);
    }

    @PostMapping("/retrieval")
    public ResponseEntity<Map<String, Object>> retrievalOnly(
            @RequestParam(defaultValue = "HYBRID") String retrievalMode) {
        // Fast path — no generation, just retrieval metrics
        return ResponseEntity.ok(evalService.runRetrievalOnlyEval(retrievalMode));
    }

    @PostMapping("/debug")
    public ResponseEntity<Map<String, Object>> debug(@RequestBody Map<String, String> body) {
        String question = body.get("question");
        String expectedSection = body.get("expectedSection");
        String retrievalMode = body.getOrDefault("retrievalMode", "HYBRID");
        return ResponseEntity.ok(evalService.debugSingleCase(question, expectedSection, retrievalMode));
    }
}
