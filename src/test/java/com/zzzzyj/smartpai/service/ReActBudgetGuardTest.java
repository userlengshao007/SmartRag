package com.zzzzyj.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReActBudgetGuardTest {

    @Test
    void detectsRepeatedIdenticalToolCallsAfterWindowIsReached() {
        ReActBudgetGuard guard = new ReActBudgetGuard(3, new ObjectMapper());
        List<LlmProviderRouter.ToolCallDecision> calls = List.of(
                new LlmProviderRouter.ToolCallDecision("call-1", "search_knowledge", Map.of("query", "SmartRag"))
        );

        assertFalse(guard.recordAndIsStagnant(calls));
        assertFalse(guard.recordAndIsStagnant(calls));
        assertTrue(guard.recordAndIsStagnant(calls));
    }

    @Test
    void clearsStagnationWindowWhenToolCallsStop() {
        ReActBudgetGuard guard = new ReActBudgetGuard(3, new ObjectMapper());
        List<LlmProviderRouter.ToolCallDecision> calls = List.of(
                new LlmProviderRouter.ToolCallDecision("call-1", "search_knowledge", Map.of("query", "SmartRag"))
        );

        assertFalse(guard.recordAndIsStagnant(calls));
        assertFalse(guard.recordAndIsStagnant(List.of()));
        assertFalse(guard.recordAndIsStagnant(calls));
        assertFalse(guard.recordAndIsStagnant(calls));
    }
}
