package com.ragpipeline.service;

import com.ragpipeline.model.*;
import com.ragpipeline.repository.ChunkRepository;
import com.ragpipeline.repository.EvalDatasetRepository;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvalServiceTest {

    @Mock
    private EvalDatasetRepository evalDatasetRepository;

    @Mock
    private ChunkRepository chunkRepository;

    @Mock
    private QdrantHybridRetrievalService retrievalService;

    @Mock
    private GenerationService generationService;

    @Mock
    private BgeRerankerService rerankerService;

    @Mock
    private ChatLanguageModel chatLanguageModel;

    @InjectMocks
    private EvalService evalService;

    private UUID chunkId;
    private EvalCase evalCase;
    private Chunk chunk;

    @BeforeEach
    void setUp() {
        chunkId = UUID.randomUUID();
        evalCase = EvalCase.builder()
                .id(UUID.randomUUID())
                .question("What is the refund window?")
                .expectedAnswer("14 days")
                .sourceSection("Refund Window")
                .difficulty("EASY")
                .category("Refunds")
                .build();

        chunk = Chunk.builder()
                .id(chunkId)
                .content("Refunds are accepted within 14 days of purchase.")
                .sectionHeading("1.1 Refund Window")
                .build();
    }

    @Test
    void runEval_ComputesMetricsSuccessfully() {
        when(evalDatasetRepository.findAll()).thenReturn(List.of(evalCase));
        when(chunkRepository.findAllIndexableChunks()).thenReturn(List.of(chunk));

        RetrievedChunk retrieved = RetrievedChunk.builder()
                .chunkId(chunkId)
                .content("Refunds are accepted within 14 days of purchase.")
                .sectionHeading("1.1 Refund Window")
                .rerankerScore(0.92)
                .build();

        when(retrievalService.retrieveTopK(anyString(), anyString(), eq(20)))
                .thenReturn(List.of(retrieved));

        QueryResponse response = QueryResponse.builder()
                .answer("The standard refund window is 14 days [1].")
                .citations(List.of(Citation.builder().index(1).chunkId(chunkId).verified(true).build()))
                .build();

        when(generationService.generateFromChunks(any(), anyList())).thenReturn(response);
        when(rerankerService.scorePair(anyString(), anyString())).thenReturn(0.88);
        when(chatLanguageModel.generate(anyString())).thenReturn("1.0");

        EvalReport report = evalService.runEval("HYBRID");

        assertNotNull(report);
        assertEquals("HYBRID", report.getRetrievalMode());
        assertEquals(1, report.getTotalCases());
        assertEquals(1.0, report.getMrr5());
        assertEquals(1.0, report.getRecall20());
        assertEquals(1.0, report.getAvgCorrectness());
        assertEquals(0.88, report.getAvgFaithfulness(), 0.01);
        assertEquals(1.0, report.getAvgCitationAccuracy());
        assertNotNull(report.summary());
    }

    @Test
    void runRetrievalOnlyEval_ComputesRetrievalMetrics() {
        when(evalDatasetRepository.findAll()).thenReturn(List.of(evalCase));
        when(chunkRepository.findAllIndexableChunks()).thenReturn(List.of(chunk));

        RetrievedChunk retrieved = RetrievedChunk.builder()
                .chunkId(chunkId)
                .content("Refunds are accepted within 14 days.")
                .sectionHeading("1.1 Refund Window")
                .build();

        when(retrievalService.retrieveTopK(anyString(), anyString(), eq(20)))
                .thenReturn(List.of(retrieved));

        Map<String, Object> result = evalService.runRetrievalOnlyEval("HYBRID");

        assertEquals("HYBRID", result.get("retrievalMode"));
        assertEquals(1, result.get("totalCases"));
        assertEquals(1.0, (Double) result.get("mrr5"));
        assertEquals(1.0, (Double) result.get("recall20"));
    }
}
