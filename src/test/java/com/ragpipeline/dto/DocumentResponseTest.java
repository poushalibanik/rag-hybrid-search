package com.ragpipeline.dto;

import com.ragpipeline.model.Document;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DocumentResponseTest {
    @Test
    void mapsPublicFieldsWithoutExposingRawContentOrHash() {
        Document document = Document.builder()
                .id(UUID.randomUUID())
                .fileName("policy.docx")
                .fileType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .rawContent("confidential source text")
                .contentHash("internal-hash")
                .status("INDEXED")
                .chunkingStrategy("RECURSIVE")
                .organization("TechCorp")
                .authority("AUTHORITATIVE")
                .documentType("POLICY")
                .current(true)
                .build();

        DocumentResponse response = DocumentResponse.from(document);

        assertEquals(document.getId(), response.id());
        assertEquals("policy.docx", response.fileName());
        assertEquals("INDEXED", response.status());
        assertFalse(java.util.Arrays.stream(DocumentResponse.class.getRecordComponents())
                .map(component -> component.getName())
                .anyMatch(name -> name.equals("rawContent") || name.equals("contentHash")));
    }
}
