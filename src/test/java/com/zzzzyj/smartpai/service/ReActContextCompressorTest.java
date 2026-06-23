package com.zzzzyj.smartpai.service;

import com.zzzzyj.smartpai.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReActContextCompressorTest {

    @Test
    void compressesOldTurnsAtUserBoundaryAndKeepsRecentTurns() {
        AiProperties aiProperties = aiProperties(8000, 0.5d, 2);
        ChatTokenEstimator estimator = mock(ChatTokenEstimator.class);
        LlmProviderRouter router = mock(LlmProviderRouter.class);
        ReActContextCompressor compressor = new ReActContextCompressor(aiProperties, estimator, router);
        List<Map<String, Object>> messages = new ArrayList<>(List.of(
                message("system", "rules"),
                message("user", "old question"),
                message("assistant", "old answer"),
                message("user", "recent question 1"),
                message("assistant", "recent answer 1"),
                message("user", "recent question 2")
        ));

        when(estimator.estimateReActRequestTokens(eq(messages), any())).thenReturn(5000, 2400);
        when(router.summarizeMessagesForCompaction(eq("u1"), any(), anyInt())).thenReturn("旧历史摘要");

        ReActContextCompressor.CompressionResult result =
                compressor.compressIfNeeded("u1", messages, List.of());

        assertTrue(result.compressed());
        assertEquals("system", messages.get(0).get("role"));
        assertEquals("[已压缩的历史对话摘要]\n旧历史摘要", messages.get(1).get("content"));
        assertEquals("assistant", messages.get(2).get("role"));
        assertEquals("recent question 1", messages.get(3).get("content"));
        assertEquals("recent question 2", messages.get(5).get("content"));
        verify(router).summarizeMessagesForCompaction(eq("u1"), any(), anyInt());
    }

    @Test
    void skipsCompressionWhenRecentTurnsWouldConsumeAllHistory() {
        AiProperties aiProperties = aiProperties(8000, 0.5d, 3);
        ChatTokenEstimator estimator = mock(ChatTokenEstimator.class);
        LlmProviderRouter router = mock(LlmProviderRouter.class);
        ReActContextCompressor compressor = new ReActContextCompressor(aiProperties, estimator, router);
        List<Map<String, Object>> messages = new ArrayList<>(List.of(
                message("system", "rules"),
                message("user", "question 1"),
                message("assistant", "answer 1"),
                message("user", "question 2")
        ));

        when(estimator.estimateReActRequestTokens(eq(messages), any())).thenReturn(5000);

        ReActContextCompressor.CompressionResult result =
                compressor.compressIfNeeded("u1", messages, List.of());

        assertFalse(result.compressed());
        assertEquals(4, messages.size());
        verify(router, never()).summarizeMessagesForCompaction(any(), any(), anyInt());
    }

    private AiProperties aiProperties(int maxContextTokens, double triggerRatio, int retainRecentRounds) {
        AiProperties properties = new AiProperties();
        properties.getGeneration().setMaxContextTokens(maxContextTokens);
        properties.getGeneration().setCompressionTriggerRatio(triggerRatio);
        properties.getGeneration().setCompressionRetainRecentRounds(retainRecentRounds);
        properties.getGeneration().setCompressionSummaryMaxTokens(300);
        return properties;
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }
}
