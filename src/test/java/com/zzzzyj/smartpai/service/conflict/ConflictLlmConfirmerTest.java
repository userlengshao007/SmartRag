package com.zzzzyj.smartpai.service.conflict;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzzzyj.smartpai.config.DocumentConflictProperties;
import com.zzzzyj.smartpai.service.LlmProviderRouter;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConflictLlmConfirmerTest {
    private final DocumentConflictProperties properties = new DocumentConflictProperties();
    private final LlmProviderRouter router = mock(LlmProviderRouter.class);
    private final ConflictLlmConfirmer confirmer = new ConflictLlmConfirmer(properties, router, new ObjectMapper());
    private final ConflictCandidate candidate = new ConflictCandidate(
            new ConflictCandidate.ChunkRef("new", 1, "超时2秒"),
            new ConflictCandidate.ChunkRef("old", 1, "超时24秒"), 0.95, 1, Set.of("2"), Set.of("24"), null);

    @Test
    void disabledConfirmationMakesNoModelCall() {
        assertEquals(List.of(candidate), confirmer.confirm(List.of(candidate)));
        verifyNoInteractions(router);
    }

    @Test
    void retainsMissingAndMalformedVerdictsButDropsExplicitFalse() throws Exception {
        var malformed = confirmer.parseVerdicts("[{\"id\":1,\"conflict\":\"unknown\"}]");
        assertEquals(List.of(candidate), confirmer.applyVerdicts(List.of(candidate), malformed));
        var negative = confirmer.parseVerdicts("[{\"id\":1,\"conflict\":false}]");
        assertTrue(confirmer.applyVerdicts(List.of(candidate), negative).isEmpty());
    }

    @Test
    void modelFailurePreservesRuleCandidate() {
        properties.getLlmConfirm().setEnabled(true);
        when(router.completeReActTurn(anyString(), anyList(), anyList(), anyInt())).thenThrow(new RuntimeException("offline"));
        assertEquals(List.of(candidate), confirmer.confirm(List.of(candidate)));
    }
}
