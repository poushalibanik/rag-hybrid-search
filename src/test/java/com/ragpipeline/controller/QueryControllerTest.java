package com.ragpipeline.controller;

import com.ragpipeline.model.QueryRequest;
import com.ragpipeline.model.QueryResponse;
import com.ragpipeline.service.GenerationService;
import com.ragpipeline.service.QdrantHybridRetrievalService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QueryControllerTest {
    @Test
    void delegatesRetrievalThenGeneration() {
        QdrantHybridRetrievalService retrieval = mock(QdrantHybridRetrievalService.class);
        GenerationService generation = mock(GenerationService.class);
        QueryRequest request = new QueryRequest();
        request.setQuestion("What is the refund window?");
        request.setRetrievalMode("HYBRID");
        QueryResponse expected = QueryResponse.builder().answer("14 days [1]").build();
        when(retrieval.retrieve("What is the refund window?", "HYBRID")).thenReturn(List.of());
        when(generation.generateFromChunks(eq(request), anyList())).thenReturn(expected);

        assertSame(expected, new QueryController(retrieval, generation).ask(request));
    }
}
