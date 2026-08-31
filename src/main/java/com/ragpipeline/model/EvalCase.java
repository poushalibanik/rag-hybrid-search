package com.ragpipeline.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "eval_cases")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalCase {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "question", nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(name = "expected_answer", nullable = false, columnDefinition = "TEXT")
    private String expectedAnswer;

    @Column(name = "source_section")
    private String sourceSection;   // e.g. "1.1 Standard Refund Window"

    @Column(name = "expected_chunk_ids", columnDefinition = "TEXT[]")
    private List<String> expectedChunkIds;

    @Column(name = "difficulty")
    private String difficulty;           // EASY / MEDIUM / HARD

    @Column(name = "category")
    private String category;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (difficulty == null) {
            difficulty = "MEDIUM";
        }
    }
}
