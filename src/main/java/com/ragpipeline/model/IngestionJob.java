package com.ragpipeline.model;
import lombok.*; import java.util.UUID;
@Data @NoArgsConstructor @AllArgsConstructor @Builder public class IngestionJob { private UUID jobId; private UUID documentId; private String fileName; private String chunkingStrategy; }
