package com.ragpipeline.integration;

import com.ragpipeline.controller.DocumentController;
import com.ragpipeline.controller.QueryController;
import com.ragpipeline.model.Document;
import com.ragpipeline.model.QueryResponse;
import com.ragpipeline.repository.DocumentRepository;
import com.ragpipeline.repository.IngestionJobRepository;
import com.ragpipeline.service.DocumentIngestionService;
import com.ragpipeline.service.EmbeddingService;
import com.ragpipeline.service.GenerationService;
import com.ragpipeline.service.QdrantHybridRetrievalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP integration tests for public API contracts; infrastructure dependencies are mocked at the boundary. */
@WebMvcTest(controllers = {DocumentController.class, QueryController.class})
class DocumentQueryApiIntegrationTest {
    @Autowired MockMvc mvc;
    @MockBean DocumentIngestionService ingestion;
    @MockBean EmbeddingService embedding;
    @MockBean DocumentRepository documents;
    @MockBean IngestionJobRepository jobs;
    @MockBean QdrantHybridRetrievalService retrieval;
    @MockBean GenerationService generation;

    @Test
    void duplicateUploadReturnsTheSameDocumentIdWithoutExposingRawContent() throws Exception {
        UUID id = UUID.randomUUID();
        Document existing = Document.builder().id(id).fileName("policy.txt").fileType("text/plain")
                .status("INDEXED").chunkingStrategy("RECURSIVE").contentHash("internal-only").rawContent("secret text").build();
        when(ingestion.ingest(any(), any(), any(), any(), any(), any())).thenReturn(existing);
        MockMultipartFile file = new MockMultipartFile("file", "policy.txt", "text/plain", "duplicate body".getBytes());

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(multipart("/api/v1/documents/ingest").file(file).param("chunkingStrategy", "RECURSIVE"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.id").value(id.toString()))
                    .andExpect(jsonPath("$.status").value("INDEXED"))
                    .andExpect(jsonPath("$.rawContent").doesNotExist())
                    .andExpect(jsonPath("$.contentHash").doesNotExist());
        }
        verify(ingestion, times(2)).ingest(any(), any(), any(), any(), any(), any());
    }

    @Test
    void unsupportedQuestionReturnsTheSafeNoAnswerResponse() throws Exception {
        when(retrieval.retrieve("What is the contractor work-from-home allowance?", "HYBRID")).thenReturn(List.of());
        when(generation.generateFromChunks(any(), anyList())).thenReturn(QueryResponse.builder()
                .answer("I do not know based on the indexed documents.").retrievedChunks(List.of()).citations(List.of()).build());

        mvc.perform(post("/api/v1/query/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"What is the contractor work-from-home allowance?\",\"retrievalMode\":\"HYBRID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("I do not know based on the indexed documents."))
                .andExpect(jsonPath("$.citations").isEmpty())
                .andExpect(jsonPath("$.retrievedChunks").isEmpty());
    }
}
