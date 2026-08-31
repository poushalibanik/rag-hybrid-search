package com.ragpipeline.model;
import lombok.*;
@Data @Builder @NoArgsConstructor @AllArgsConstructor public class ConfidenceScore { private double score; private double averageRerankerScore; private double citationVerificationRate; private int retrievedChunks; }
