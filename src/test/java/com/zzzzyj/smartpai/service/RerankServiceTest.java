package com.zzzzyj.smartpai.service;

import com.zzzzyj.smartpai.client.RerankClient;
import com.zzzzyj.smartpai.config.RerankProperties;
import com.zzzzyj.smartpai.entity.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RerankServiceTest {
    private final RerankClient client = mock(RerankClient.class);
    private final RerankProperties properties = new RerankProperties();
    private final RerankService service = new RerankService(client, properties);

    @Test
    void reordersAndFiltersWithoutTruncatingOriginalText() {
        properties.setMaxDocumentChars(3);
        SearchResult first = new SearchResult("a", 0, "first text", 0.03);
        SearchResult second = new SearchResult("b", 0, "second text", 0.02);
        when(client.rerank("q", List.of("fir", "sec"))).thenReturn(List.of(
                new RerankClient.RerankHit(1, 0.9), new RerankClient.RerankHit(0, 0.09)));
        RerankService.Outcome outcome = service.rerankWithTrace("q", List.of(first, second), 2);
        assertEquals(1, outcome.results().size());
        assertEquals("b", outcome.results().get(0).getFileMd5());
        assertEquals("second text", outcome.results().get(0).getTextContent());
        assertEquals("RERANK", outcome.results().get(0).getRetrievalMode());
        assertEquals(0.9, outcome.results().get(0).getScore());
        assertEquals(0.02, second.getScore());
        assertEquals(0.03, first.getScore());
        assertEquals(2, outcome.ranked().size());
        assertEquals(1, outcome.thresholdDroppedInTopK());
        verify(client, times(1)).rerank("q", List.of("fir", "sec"));
    }

    @Test
    void failureKeepsRrfOrderAndScores() {
        SearchResult first = new SearchResult("a", 0, "text", 0.03);
        SearchResult second = new SearchResult("b", 0, "text", 0.02);
        when(client.rerank(anyString(), anyList())).thenThrow(new IllegalStateException("timeout"));
        assertEquals(List.of(first), service.rerank("q", List.of(first, second), 1));
        assertEquals(0.03, first.getScore());
    }

    @Test
    void allLowScoresReturnEmptyRatherThanFallback() {
        when(client.rerank(anyString(), anyList())).thenReturn(List.of(new RerankClient.RerankHit(0, 0.01)));
        assertTrue(service.rerank("q", List.of(new SearchResult("a", 0, "text", 0.03)), 5).isEmpty());
    }

    @Test
    void disabledSkipsRemoteCallAndCandidateExpansion() {
        properties.setEnabled(false);
        assertEquals(5, service.candidateLimit(5));
        assertEquals(1, service.rerank("q", List.of(new SearchResult("a", 0, "text", 0.03)), 5).size());
        verifyNoInteractions(client);
    }
}
