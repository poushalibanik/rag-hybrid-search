package com.ragpipeline.model;

import jakarta.persistence.*; import lombok.*; import java.time.Instant; import java.util.*;
@Entity @Table(name="documents") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Document {
 @Id @GeneratedValue private UUID id; @Column(nullable=false) private String fileName; private String sourcePath;
 @Column(nullable=false) private String fileType; @Column(columnDefinition="TEXT") private String rawContent;
 @Column(nullable=false) private String status; @Column(nullable=false) private String chunkingStrategy;
 private String organization; private String authority; private String documentType;
 @Column(name="is_current") private Boolean current;
 private Instant createdAt; private Instant updatedAt;
 @OneToMany(mappedBy="document", cascade=CascadeType.ALL, orphanRemoval=true) @Builder.Default private List<Chunk> chunks=new ArrayList<>();
 @PrePersist void created(){if(status==null)status="PENDING";if(chunkingStrategy==null)chunkingStrategy="RECURSIVE";createdAt=Instant.now();updatedAt=createdAt;} @PreUpdate void updated(){updatedAt=Instant.now();}
}
