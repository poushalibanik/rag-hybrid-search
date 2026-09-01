package com.ragpipeline.service;

import com.ragpipeline.model.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.*;

@Service
@RequiredArgsConstructor
public class GenerationService {

    private final ChatLanguageModel model;
    private final CitationVerificationService verifier;
    @Value("${rag.generation.hard-abstention-score:0.30}")
    double hardAbstentionScore;

    public String generate(String question, List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty() || chunks.getFirst().getRerankerScore() < hardAbstentionScore) {
            return "I do not know based on the indexed documents.";
        }
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            context.append("\n[").append(i + 1).append("] ").append(chunks.get(i).getContent());
        }
        return model.generate("""
                Answer the question directly and concisely using only the supplied context.
                Include only information needed to answer the question; do not add related policies, examples, or caveats unless explicitly asked.
                Cite every factual claim with the matching [N] context marker.
                If the context does not support the answer, say exactly: I do not know based on the indexed documents.
                
                Question: %s
                Context:%s
                """.formatted(question, context));
    }

    public List<Citation> citations(String answer, List<RetrievedChunk> cs) {
        List<Citation> r = new ArrayList<>();
        for (int i = 0; i < cs.size(); i++) {
            int index = i + 1;
            if (!answer.contains("[" + index + "]")) continue;
            RetrievedChunk c = cs.get(i);
            r.add(Citation.builder()
                    .index(index)
                    .chunkId(c.getChunkId())
                    .fileName(c.getFileName())
                    .sectionHeading(c.getSectionHeading())
                    .relevantExcerpt(c.getContent().substring(0, Math.min(300, c.getContent().length())))
                    .build());
        }
        return r;
    }

    public QueryResponse generateFromChunks(QueryRequest req, List<RetrievedChunk> chunks) {
        String answer = generate(req.getQuestion(), chunks);
        List<Citation> cites = verifier.verify(answer, citations(answer, chunks), chunks);
        double avg = chunks.stream().mapToDouble(RetrievedChunk::getRerankerScore).average().orElse(0.0);
        long verified = cites.stream().filter(Citation::isVerified).count();
        double rate = cites.isEmpty() ? 0.0 : (double) verified / cites.size();
        return QueryResponse.builder()
                .answer(answer)
                .retrievalMode(req.getRetrievalMode())
                .retrievedChunks(chunks)
                .citations(cites)
                .confidence(ConfidenceScore.builder()
                        .averageRerankerScore(avg)
                        .citationVerificationRate(rate)
                        .retrievedChunks(chunks.size())
                        // Confidence is intentionally zero for an abstention: citation scores do not make an unsupported answer trustworthy.
                        .score(answer.equals("I do not know based on the indexed documents.") ? 0.0 : (avg + rate) / 2.0)
                        .build())
                .build();
    }
}
