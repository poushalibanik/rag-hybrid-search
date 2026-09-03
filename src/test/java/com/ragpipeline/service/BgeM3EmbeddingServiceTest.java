package com.ragpipeline.service;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.ragpipeline.model.RetrievedChunk;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Runs against locally downloaded models; skip explicitly with -DskipModelTests=true. */
class BgeM3EmbeddingServiceTest {
    private static OrtEnvironment environment;
    private static OrtSession m3Session;
    private static OrtSession rerankerSession;
    private static HuggingFaceTokenizer m3Tokenizer;
    private static HuggingFaceTokenizer rerankerTokenizer;

    @BeforeAll
    static void loadModels() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(Boolean.getBoolean("skipModelTests"),
                "Model integration tests were disabled with -DskipModelTests=true");
        Path m3 = Path.of("models/bge-m3/model.onnx");
        Path reranker = Path.of("models/bge-reranker-v2-m3/model.onnx");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(m3) && Files.isRegularFile(reranker),
                "Local ONNX model files are required for model integration tests");
        environment = OrtEnvironment.getEnvironment();
        m3Session = environment.createSession(m3.toString(), new OrtSession.SessionOptions());
        rerankerSession = environment.createSession(reranker.toString(), new OrtSession.SessionOptions());
        m3Tokenizer = HuggingFaceTokenizer.newInstance(Path.of("models/bge-m3/tokenizer.json"));
        rerankerTokenizer = HuggingFaceTokenizer.newInstance(Path.of("models/bge-reranker-v2-m3/tokenizer.json"));
    }

    @AfterAll
    static void closeModels() throws Exception {
        if (m3Tokenizer != null) m3Tokenizer.close();
        if (rerankerTokenizer != null) rerankerTokenizer.close();
        if (m3Session != null) m3Session.close();
        if (rerankerSession != null) rerankerSession.close();
    }

    @Test
    void embedsTextWithNormalizedDenseAndSparseVectors() throws Exception {
        BgeM3EmbeddingService service = new BgeM3EmbeddingService(m3Session, environment, m3Tokenizer);
        setMaxLength(service, 128);
        String text = "TechCorp refunds eligible subscriptions within fourteen days.";

        var embedding = service.embed(text);

        assertEquals(1024, embedding.getDenseVector().length);
        assertFalse(embedding.getSparseVector().isEmpty());
        assertEquals(text, embedding.getOriginalText());
        double squaredNorm = 0; for (float value : embedding.getDenseVector()) squaredNorm += value * value;
        assertEquals(1.0, Math.sqrt(squaredNorm), 0.01);
    }

    @Test
    void reranksCandidateChunksAndProducesLogisticScores() throws Exception {
        BgeRerankerService service = new BgeRerankerService(rerankerSession, environment, rerankerTokenizer);
        setMaxLength(service, 128);
        double score = service.scorePair("What is the refund window?", "The full refund window is 14 days.");
        List<RetrievedChunk> ranked = service.rerank("What is the refund window?", List.of(
                RetrievedChunk.builder().content("The full refund window is 14 days.").build(),
                RetrievedChunk.builder().content("Deployments run Monday to Thursday.").build()), 1);

        assertTrue(score > 0 && score < 1);
        assertEquals(1, ranked.size());
        assertNotNull(ranked.getFirst().getRerankerScore());
        assertEquals(2, service.scoreAll("refund", List.of("14 days", "deployment")).size());
    }

    private static void setMaxLength(Object service, int maxLength) throws Exception {
        Field field = service.getClass().getDeclaredField("maxLength");
        field.setAccessible(true);
        field.setInt(service, maxLength);
    }
}
