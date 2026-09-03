package com.ragpipeline.controller;

import com.ragpipeline.model.EvalReport;
import com.ragpipeline.service.EvalService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvalControllerTest {
    @Test
    void exposesSingleComparisonRetrievalAndDebugEvaluationOperations() {
        EvalService service = mock(EvalService.class);
        EvalReport report = EvalReport.builder().retrievalMode("HYBRID").totalCases(20).build();
        when(service.runEval("HYBRID")).thenReturn(report);
        when(service.runComparison()).thenReturn(Map.of("HYBRID", report));
        when(service.runRetrievalOnlyEval("HYBRID")).thenReturn(Map.of("mrr5", 1.0));
        when(service.debugSingleCase("question", "section", "HYBRID")).thenReturn(Map.of("foundInTop5", true));
        EvalController controller = new EvalController(service);

        assertSame(report, controller.run("HYBRID").getBody());
        assertEquals(report, controller.compare().getBody().get("HYBRID"));
        assertEquals(1.0, controller.retrievalOnly("HYBRID").getBody().get("mrr5"));
        assertEquals(true, controller.debug(Map.of("question", "question", "expectedSection", "section")).getBody().get("foundInTop5"));
    }
}
