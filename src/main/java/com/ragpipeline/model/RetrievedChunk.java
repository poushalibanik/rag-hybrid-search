package com.ragpipeline.model;
import lombok.*; import java.util.UUID;
@Data @Builder @NoArgsConstructor @AllArgsConstructor public class RetrievedChunk { private UUID chunkId,documentId; private String fileName,content,sectionHeading; private double denseScore,sparseScore,rrfScore,rerankerScore; private int chunkIndex; }
