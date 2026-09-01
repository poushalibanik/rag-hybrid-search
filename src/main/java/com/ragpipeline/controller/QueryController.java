package com.ragpipeline.controller;

import com.ragpipeline.model.QueryRequest;
import com.ragpipeline.model.QueryResponse;
import com.ragpipeline.service.GenerationService;
import com.ragpipeline.service.QdrantHybridRetrievalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/query")
@RequiredArgsConstructor
public class QueryController {
    private final QdrantHybridRetrievalService retrieval;
    private final GenerationService generation;

    @PostMapping("/ask")
    public QueryResponse ask(@Valid @RequestBody QueryRequest request) {
        return generation.generateFromChunks(request,
                retrieval.retrieve(request.getQuestion(), request.getRetrievalMode()));
    }
}
