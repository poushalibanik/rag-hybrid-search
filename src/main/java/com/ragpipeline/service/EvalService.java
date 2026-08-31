package com.ragpipeline.service;

import com.ragpipeline.model.*;
import com.ragpipeline.repository.ChunkRepository;
import com.ragpipeline.repository.EvalDatasetRepository;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * COMPLETE EVALUATION SERVICE
 *
 * Computes 5 metrics across 3 retrieval modes for every eval case:
 *
 *  1. MRR@5         — Mean Reciprocal Rank: was the right chunk in the top 5?
 *  2. Recall@20     — was the right chunk anywhere in the top 20?
 *  3. Faithfulness  — are all claims in the answer grounded in the retrieved chunks?
 *  4. Correctness   — does the answer match the expected answer? (Qwen3-as-judge)
 *  5. Citation acc. — what fraction of [N] citations are verified by the BGE reranker?
 *
 * HOW CHUNK MATCHING WORKS:
 *   Each eval_case has a source_section (e.g. "1.1 Standard Refund Window").
 *   After ingestion, chunks inherit their section_heading from the ChunkingService.
 *   We match by section_heading CONTAINS source_section (case-insensitive).
 *   This means we don't need to know the exact chunk UUID before ingestion —
 *   the section heading acts as a stable identifier.
 *
 * USAGE:
 *   POST /api/v1/eval/run?retrievalMode=HYBRID
 *   POST /api/v1/eval/run?retrievalMode=DENSE_ONLY
 *   POST /api/v1/eval/run?retrievalMode=SPARSE_ONLY
 *   POST /api/v1/eval/run/compare   ← runs all 3 modes and returns side-by-side
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalService {

    private final EvalDatasetRepository evalDatasetRepository;
    private final ChunkRepository chunkRepository;
    private final QdrantHybridRetrievalService retrievalService;
    private final GenerationService generationService;
    private final BgeRerankerService rerankerService;
    private final ChatLanguageModel chatLanguageModel;

    // ─────────────────────────────────────────────
    // PUBLIC: run eval for one retrieval mode
    // ─────────────────────────────────────────────

    public EvalReport runEval(String retrievalMode) {
        List<EvalCase> cases = evalDatasetRepository.findAll();
        log.info("Running eval: {} cases, mode={}", cases.size(), retrievalMode);

        List<EvalCaseResult> caseResults = new ArrayList<>();

        for (EvalCase evalCase : cases) {
            EvalCaseResult result = evaluateSingleCase(evalCase, retrievalMode);
            caseResults.add(result);
            log.info("Case [{}] mode={} mrr={} correctness={:.2f} faithful={:.2f}",
                    evalCase.getId(), retrievalMode,
                    result.getReciprocalRank(), result.getCorrectnessScore(),
                    result.getFaithfulnessScore());
        }

        return buildReport(retrievalMode, caseResults);
    }

    // ─────────────────────────────────────────────
    // PUBLIC: compare all 3 modes side by side
    // ─────────────────────────────────────────────

    public Map<String, EvalReport> runComparison() {
        Map<String, EvalReport> results = new LinkedHashMap<>();
        for (String mode : List.of("HYBRID", "DENSE_ONLY", "SPARSE_ONLY")) {
            results.put(mode, runEval(mode));
        }
        return results;
    }

    // ─────────────────────────────────────────────
    // PUBLIC: run retrieval only (fast path)
    // ─────────────────────────────────────────────

    public Map<String, Object> runRetrievalOnlyEval(String retrievalMode) {
        List<EvalCase> cases = evalDatasetRepository.findAll();
        List<Double> rrList = new ArrayList<>();
        int recallCount = 0;

        for (EvalCase evalCase : cases) {
            List<RetrievedChunk> top20 = retrievalService.retrieveTopK(evalCase.getQuestion(), retrievalMode, 20);
            List<RetrievedChunk> top5 = top20.stream().limit(5).collect(Collectors.toList());
            List<UUID> correctChunkIds = findCorrectChunkIds(evalCase.getSourceSection());
            double rr = computeReciprocalRank(top5, correctChunkIds);
            boolean recall = top20.stream().anyMatch(c -> c.getChunkId() != null && correctChunkIds.contains(c.getChunkId()));
            rrList.add(rr);
            if (recall) recallCount++;
        }

        double mrr5 = rrList.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double recall20 = cases.isEmpty() ? 0.0 : (double) recallCount / cases.size();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("retrievalMode", retrievalMode);
        response.put("totalCases", cases.size());
        response.put("mrr5", mrr5);
        response.put("recall20", recall20);
        return response;
    }

    // ─────────────────────────────────────────────
    // PUBLIC: debug single case
    // ─────────────────────────────────────────────

    public Map<String, Object> debugSingleCase(String question, String expectedSection, String retrievalMode) {
        List<RetrievedChunk> top20 = retrievalService.retrieveTopK(question, retrievalMode, 20);
        List<RetrievedChunk> top5 = top20.stream().limit(5).collect(Collectors.toList());
        List<UUID> correctChunkIds = findCorrectChunkIds(expectedSection);
        double rr = computeReciprocalRank(top5, correctChunkIds);
        boolean recall = top20.stream().anyMatch(c -> c.getChunkId() != null && correctChunkIds.contains(c.getChunkId()));

        QueryRequest req = new QueryRequest();
        req.setQuestion(question);
        req.setRetrievalMode(retrievalMode);
        QueryResponse response = generationService.generateFromChunks(req, top5);

        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("question", question);
        debug.put("expectedSection", expectedSection);
        debug.put("retrievalMode", retrievalMode);
        debug.put("reciprocalRank", rr);
        debug.put("foundInTop5", rr > 0.0);
        debug.put("foundInTop20", recall);
        debug.put("top5ChunkIds", top5.stream().map(c -> c.getChunkId() != null ? c.getChunkId().toString() : "null").collect(Collectors.toList()));
        debug.put("correctChunkIds", correctChunkIds.stream().map(UUID::toString).collect(Collectors.toList()));
        debug.put("generatedAnswer", response.getAnswer());
        debug.put("citations", response.getCitations());
        return debug;
    }

    // ─────────────────────────────────────────────
    // CORE: evaluate one question in one mode
    // ─────────────────────────────────────────────

    private EvalCaseResult evaluateSingleCase(EvalCase evalCase, String retrievalMode) {
        // ── STEP 1: Retrieve top 20 candidates (before reranking)
        List<RetrievedChunk> top20 = retrievalService.retrieveTopK(evalCase.getQuestion(), retrievalMode, 20);
        List<RetrievedChunk> top5  = top20.stream().limit(5).collect(Collectors.toList());

        // ── STEP 2: Find which chunks in our DB match this eval case's source section
        List<UUID> correctChunkIds = findCorrectChunkIds(evalCase.getSourceSection());
        log.debug("Case '{}': {} correct chunk(s) found for section '{}'",
                evalCase.getQuestion().substring(0, Math.min(40, evalCase.getQuestion().length())),
                correctChunkIds.size(), evalCase.getSourceSection());

        // ── STEP 3: MRR@5 — find the rank of the first correct chunk in top-5
        double reciprocalRank = computeReciprocalRank(top5, correctChunkIds);

        // ── STEP 4: Recall@20 — was any correct chunk in top-20?
        boolean recallAt20 = top20.stream()
                .anyMatch(c -> c.getChunkId() != null && correctChunkIds.contains(c.getChunkId()));

        // ── STEP 5: Generate answer from top-5
        QueryRequest req = new QueryRequest();
        req.setQuestion(evalCase.getQuestion());
        req.setRetrievalMode(retrievalMode);
        QueryResponse response = generationService.generateFromChunks(req, top5);

        // ── STEP 6: Faithfulness — are answer claims grounded in top-5 chunks?
        double faithfulness = scoreFaithfulness(response.getAnswer(), top5);

        // ── STEP 7: Correctness — Qwen3 judges actual vs expected
        double correctness = scoreCorrectness(
                evalCase.getExpectedAnswer(),
                response.getAnswer(),
                evalCase.getQuestion());

        // ── STEP 8: Citation accuracy — verified citations / total citations
        long verifiedCount = response.getCitations().stream()
                .filter(Citation::isVerified).count();
        double citationAccuracy = response.getCitations().isEmpty() ? 0.0
                : (double) verifiedCount / response.getCitations().size();

        return EvalCaseResult.builder()
                .evalCaseId(evalCase.getId())
                .question(evalCase.getQuestion())
                .expectedAnswer(evalCase.getExpectedAnswer())
                .actualAnswer(response.getAnswer())
                .sourceSection(evalCase.getSourceSection())
                .difficulty(evalCase.getDifficulty())
                .category(evalCase.getCategory())
                .retrievalMode(retrievalMode)
                .reciprocalRank(reciprocalRank)
                .recallAt20(recallAt20)
                .correctnessScore(correctness)
                .faithfulnessScore(faithfulness)
                .citationAccuracy(citationAccuracy)
                .top5ChunkIds(top5.stream()
                        .map(c -> c.getChunkId() != null ? c.getChunkId().toString() : "null")
                        .collect(Collectors.toList()))
                .correctChunkIds(correctChunkIds.stream().map(UUID::toString).collect(Collectors.toList()))
                .build();
    }

    // ─────────────────────────────────────────────
    // METRIC 1: MRR@5
    // ─────────────────────────────────────────────

    private double computeReciprocalRank(List<RetrievedChunk> top5, List<UUID> correctIds) {
        for (int rank = 0; rank < top5.size(); rank++) {
            RetrievedChunk chunk = top5.get(rank);
            if (chunk.getChunkId() != null && correctIds.contains(chunk.getChunkId())) {
                return 1.0 / (rank + 1);  // rank is 0-indexed, so +1
            }
        }
        return 0.0;  // correct chunk not found in top-5
    }

    // ─────────────────────────────────────────────
    // METRIC 3: Faithfulness (BGE Reranker)
    // ─────────────────────────────────────────────

    private double scoreFaithfulness(String answer, List<RetrievedChunk> chunks) {
        if (chunks.isEmpty() || answer == null || answer.isBlank()) return 0.0;

        // Split answer into sentences for granular checking
        String[] sentences = answer.split("(?<=[.!?])\\s+");
        List<String> contextChunks = chunks.stream()
                .limit(3)
                .map(RetrievedChunk::getContent)
                .collect(Collectors.toList());

        double total = 0;
        int count = 0;

        for (String sentence : sentences) {
            if (sentence.strip().length() < 20) continue;  // skip very short fragments
            double maxScore = 0;
            for (String context : contextChunks) {
                double score = rerankerService.scorePair(sentence.strip(), context);
                maxScore = Math.max(maxScore, score);
            }
            total += maxScore;
            count++;
        }

        return count == 0 ? 0.5 : total / count;
    }

    // ─────────────────────────────────────────────
    // METRIC 4: Correctness (Qwen3-as-judge)
    // ─────────────────────────────────────────────

    private double scoreCorrectness(String expected, String actual, String question) {
        String prompt = String.format("""
            You are an answer evaluation system. Score how correct the ACTUAL ANSWER is
            compared to the EXPECTED ANSWER for the given question.
            
            QUESTION: %s
            
            EXPECTED ANSWER: %s
            
            ACTUAL ANSWER: %s
            
            SCORING CRITERIA:
            1.0 = Factually identical. All key facts, numbers, and details match.
            0.7 = Mostly correct. Minor omissions or rephrasing but no factual errors.
            0.4 = Partially correct. Some right facts but missing important information.
            0.1 = Mostly wrong. Significant factual errors or irrelevant answer.
            0.0 = Completely wrong or "I don't know" when the answer is knowable.
            
            RULES:
            - Treat paraphrases as correct (e.g. "5 to 7 days" == "5–7 days")
            - Numbers must match exactly to score 1.0
            - Extra correct information beyond the expected answer does not reduce the score
            - Output ONLY a single decimal number between 0.0 and 1.0. Nothing else.
            """,
            question, expected, actual);

        try {
            String response = chatLanguageModel.generate(prompt).strip();
            // Extract first number found in the response (handles "0.8" or "Score: 0.8")
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\d+\\.?\\d*").matcher(response);
            if (m.find()) {
                double score = Double.parseDouble(m.group());
                return Math.min(1.0, Math.max(0.0, score));  // clamp to [0,1]
            }
            log.warn("Could not parse correctness score from: '{}'", response);
            return 0.5;
        } catch (Exception e) {
            log.error("Qwen3 correctness scoring failed", e);
            return 0.5;
        }
    }

    // ─────────────────────────────────────────────
    // CHUNK MATCHING: find correct chunks by section heading
    // ─────────────────────────────────────────────

    private List<UUID> findCorrectChunkIds(String sourceSection) {
        if (sourceSection == null || sourceSection.isBlank()) {
            return Collections.emptyList();
        }
        return chunkRepository.findAllIndexableChunks().stream()
                .filter(chunk -> chunk.getSectionHeading() != null &&
                        chunk.getSectionHeading().toLowerCase()
                                .contains(sourceSection.toLowerCase()))
                .map(Chunk::getId)
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────
    // REPORT BUILDER
    // ─────────────────────────────────────────────

    private EvalReport buildReport(String retrievalMode, List<EvalCaseResult> caseResults) {
        double mrr5 = caseResults.stream()
                .mapToDouble(EvalCaseResult::getReciprocalRank).average().orElse(0.0);
        double recall20 = caseResults.stream()
                .mapToDouble(r -> r.isRecallAt20() ? 1.0 : 0.0).average().orElse(0.0);
        double correctness = caseResults.stream()
                .mapToDouble(EvalCaseResult::getCorrectnessScore).average().orElse(0.0);
        double faithfulness = caseResults.stream()
                .mapToDouble(EvalCaseResult::getFaithfulnessScore).average().orElse(0.0);
        double citationAcc = caseResults.stream()
                .mapToDouble(EvalCaseResult::getCitationAccuracy).average().orElse(0.0);

        // Per-difficulty breakdown (for README reporting)
        Map<String, Double> mrrByDifficulty = caseResults.stream()
                .filter(r -> r.getDifficulty() != null)
                .collect(Collectors.groupingBy(EvalCaseResult::getDifficulty,
                        Collectors.averagingDouble(EvalCaseResult::getReciprocalRank)));

        // Failure analysis: cases where MRR@5 = 0 (retrieval completely missed)
        List<String> failures = caseResults.stream()
                .filter(r -> r.getReciprocalRank() == 0.0)
                .map(r -> "Section: " + r.getSourceSection() + " | Q: " +
                        (r.getQuestion() != null ? r.getQuestion().substring(0, Math.min(60, r.getQuestion().length())) : ""))
                .collect(Collectors.toList());

        return EvalReport.builder()
                .retrievalMode(retrievalMode)
                .totalCases(caseResults.size())
                .mrr5(mrr5)
                .recall20(recall20)
                .avgCorrectness(correctness)
                .avgFaithfulness(faithfulness)
                .avgCitationAccuracy(citationAcc)
                .mrrByDifficulty(mrrByDifficulty)
                .retrievalFailures(failures)
                .caseResults(caseResults)
                .build();
    }
}
