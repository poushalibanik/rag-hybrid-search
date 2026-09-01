package com.ragpipeline.service;

import com.ragpipeline.model.BgeEmbedding;
import com.ragpipeline.model.Chunk;
import com.ragpipeline.model.Document;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/** Splits documents by structure, fixed windows, or embedding similarity between sentences. */
@Service
@RequiredArgsConstructor
public class ChunkingService {
    private static final Pattern SECTION = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)+\\s+.+?)\\s*$");
    private static final Pattern MARKDOWN = Pattern.compile("^\\s*#{1,6}\\s+(.+?)\\s*$");
    private final BgeM3EmbeddingService embeddings;
    @Value("${rag.chunking.fixed-size:512}") int size;
    @Value("${rag.chunking.overlap:64}") int overlap;
    @Value("${rag.chunking.semantic-similarity-threshold:0.62}") double semanticThreshold;

    public List<Chunk> chunk(Document document) { return chunk(document, document.getChunkingStrategy()); }
    public List<Chunk> chunk(Document document, String strategy) {
        String text = Optional.ofNullable(document.getRawContent()).orElse("").trim();
        if (text.isEmpty()) return List.of();
        String selected = Optional.ofNullable(strategy).orElse("RECURSIVE").toUpperCase(Locale.ROOT);
        List<Part> parts = switch (selected) { case "FIXED_SIZE" -> fixed(text); case "RECURSIVE" -> recursive(text); case "SEMANTIC" -> semantic(text); default -> throw new IllegalArgumentException("Unknown chunking strategy: " + strategy); };
        Set<String> seen = new HashSet<>(); List<Chunk> result = new ArrayList<>();
        for (Part part : parts) { String content = part.content().trim(); if (!content.isBlank()) result.add(Chunk.builder().document(document).content(content).chunkIndex(result.size()).sectionHeading(part.heading()).chunkingStrategy(selected).charCount(content.length()).tokenEstimate((content.length() + 3) / 4).isDuplicate(!seen.add(content)).build()); }
        return result;
    }
    private List<Part> recursive(String text) { List<Part> chunks = new ArrayList<>(); String heading = null; StringBuilder current = new StringBuilder(); for (String line : text.split("\\R")) { String trimmed = line.trim(); String candidate = heading(trimmed); if (candidate != null) { flush(chunks, current, heading); heading = candidate; continue; } if (!trimmed.isBlank()) append(chunks, current, trimmed, heading); } flush(chunks, current, heading); return chunks; }
    /** Embeds sentences and starts a chunk where neighbouring meaning diverges. */
    private List<Part> semantic(String text) {
        List<Part> output = new ArrayList<>(); String heading = null; StringBuilder current = new StringBuilder(); float[] previous = null;
        for (String paragraph : text.split("(?:\\R\\s*){2,}")) {
            String normalized = paragraph.trim(); if (normalized.isBlank()) continue; String candidate = heading(normalized);
            if (candidate != null) { flush(output, current, heading); heading = candidate; previous = null; continue; }
            for (String sentence : normalized.split("(?<=[.!?])\\s+")) {
                String value = sentence.trim(); if (value.isBlank()) continue; float[] vector = embeddings.embed(value).getDenseVector();
                boolean boundary = current.length() > 0 && (current.length() + value.length() + 1 > size || (previous != null && cosine(previous, vector) < semanticThreshold));
                if (boundary) flush(output, current, heading);
                if (current.length() > 0) current.append(' '); current.append(value); previous = vector;
            }
            if (current.length() >= size / 2) { flush(output, current, heading); previous = null; }
        }
        flush(output, current, heading); return output;
    }
    private double cosine(float[] left, float[] right) { double dot = 0, leftNorm = 0, rightNorm = 0; for (int i = 0; i < Math.min(left.length, right.length); i++) { dot += left[i] * right[i]; leftNorm += left[i] * left[i]; rightNorm += right[i] * right[i]; } return leftNorm == 0 || rightNorm == 0 ? 0 : dot / Math.sqrt(leftNorm * rightNorm); }
    private List<Part> fixed(String text) { List<Part> chunks = new ArrayList<>(); for (int start = 0; start < text.length(); start += Math.max(1, size - overlap)) chunks.add(new Part(null, text.substring(start, Math.min(text.length(), start + size)))); return chunks; }
    private void append(List<Part> chunks, StringBuilder current, String text, String heading) { if (current.length() > 0 && current.length() + text.length() + 1 > size) { String previous = current.toString(); flush(chunks, current, heading); current.append(previous.substring(Math.max(0, previous.length() - overlap))).append(' '); } current.append(text).append(' '); }
    private void flush(List<Part> chunks, StringBuilder current, String heading) { if (current.length() > 0) chunks.add(new Part(heading, current.toString())); current.setLength(0); }
    private String heading(String text) { var numeric = SECTION.matcher(text); if (numeric.matches()) return numeric.group(1); var markdown = MARKDOWN.matcher(text); return markdown.matches() ? markdown.group(1) : null; }
    private record Part(String heading, String content) { }
}
