package com.ragpipeline.model;
import lombok.*; import java.util.Map;
@Data @Builder public class BgeEmbedding { private float[] denseVector; private Map<Integer,Float> sparseVector; private String originalText; }
