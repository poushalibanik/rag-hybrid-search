package com.ragpipeline.model;
import jakarta.validation.constraints.NotBlank; import lombok.Data;
@Data public class QueryRequest { @NotBlank private String question; private String retrievalMode="HYBRID"; }
