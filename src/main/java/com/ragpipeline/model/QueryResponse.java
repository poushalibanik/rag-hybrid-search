package com.ragpipeline.model;
import lombok.*; import java.util.*;
@Data @Builder @NoArgsConstructor @AllArgsConstructor public class QueryResponse { private String answer; @Builder.Default private List<Citation> citations=new ArrayList<>(); @Builder.Default private List<RetrievedChunk> retrievedChunks=new ArrayList<>(); private ConfidenceScore confidence; private String retrievalMode; }
