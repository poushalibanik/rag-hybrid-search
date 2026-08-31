package com.ragpipeline.model;
import lombok.*; import java.util.UUID;
@Data @Builder @NoArgsConstructor @AllArgsConstructor public class Citation { private int index; private UUID chunkId; private String fileName,sectionHeading,relevantExcerpt; private boolean verified; private double verificationScore; }
