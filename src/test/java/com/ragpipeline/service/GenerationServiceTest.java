package com.ragpipeline.service;

import com.ragpipeline.model.QueryRequest;
import com.ragpipeline.model.QueryResponse;
import com.ragpipeline.model.RetrievedChunk;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class GenerationServiceTest {
    private final ChatLanguageModel model = mock(ChatLanguageModel.class);
    private final CitationVerificationService verifier = mock(CitationVerificationService.class);

    @Test
    void weakOrMissingContextAbstainsWithoutCallingTheLanguageModel() {
        GenerationService service = service();

        QueryResponse response = service.generateFromChunks(request(), List.of(
                RetrievedChunk.builder().content("unrelated text").rerankerScore(0.10).build()));

        assertEquals("I do not know based on the indexed documents.", response.getAnswer());
        assertEquals(0.0, response.getConfidence().getScore());
        assertTrue(response.getCitations().isEmpty());
        verifyNoInteractions(model);
    }

    @Test
    void groundedContextBuildsPromptAndReturnsVerifiedCitation() {
        GenerationService service = service();
        RetrievedChunk chunk = RetrievedChunk.builder().content("The minimum password length is 12 characters.")
                .rerankerScore(0.90).build();
        when(model.generate(contains("minimum password"))).thenReturn("The minimum password length is 12 characters [1].");
        when(verifier.verify(anyString(), anyList(), anyList())).thenAnswer(invocation -> invocation.getArgument(1));

        QueryResponse response = service.generateFromChunks(request(), List.of(chunk));

        assertTrue(response.getAnswer().contains("12 characters"));
        assertEquals(1, response.getCitations().size());
        assertEquals(0.45, response.getConfidence().getScore(), 0.001);
    }

    private GenerationService service() {
        GenerationService service = new GenerationService(model, verifier);
        service.hardAbstentionScore = 0.30;
        return service;
    }

    private QueryRequest request() {
        QueryRequest request = new QueryRequest();
        request.setQuestion("What is the minimum password length?");
        request.setRetrievalMode("HYBRID");
        return request;
    }
}
