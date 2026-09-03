package com.ragpipeline.service;

import com.ragpipeline.model.Citation;
import com.ragpipeline.model.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CitationVerificationServiceTest {
    @Test
    void verifiesMatchingCitationsAndLeavesMissingSourceUnverified() {
        BgeRerankerService reranker = mock(BgeRerankerService.class);
        CitationVerificationService service = new CitationVerificationService(reranker);
        UUID id = UUID.randomUUID();
        Citation supported = Citation.builder().index(1).chunkId(id).build();
        Citation missing = Citation.builder().index(2).chunkId(UUID.randomUUID()).build();
        when(reranker.scorePair(anyString(), eq("The policy allows 14 days."))).thenReturn(0.85);

        List<Citation> verified = service.verify("Refunds are allowed for 14 days [1].", List.of(supported, missing),
                List.of(RetrievedChunk.builder().chunkId(id).content("The policy allows 14 days.").build()));

        assertTrue(verified.getFirst().isVerified());
        assertEquals(0.85, verified.getFirst().getVerificationScore());
        assertFalse(verified.get(1).isVerified());
        assertEquals(0.0, verified.get(1).getVerificationScore());
    }
}
