package com.zzzzyj.smartpai.service;

import com.zzzzyj.smartpai.service.ConversationRuntimeMemory.RuntimeMemoryEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemoryManagerTest {

    @Test
    void buildsPromptContextFromRuntimeAndLongTermMemory() {
        ConversationRuntimeMemory runtimeMemory = mock(ConversationRuntimeMemory.class);
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        ChatTokenEstimator estimator = mock(ChatTokenEstimator.class);
        LlmProviderRouter router = mock(LlmProviderRouter.class);
        ChatMemoryManager manager = new ChatMemoryManager(runtimeMemory, chatMemoryService, estimator, router);

        when(runtimeMemory.list("conv-1")).thenReturn(List.of(
                new RuntimeMemoryEntry("r1", "tool", "TOOL_RESULT", "search_knowledge 返回了 3 条片段", "tool_result", "search_knowledge", 20, "now")
        ));
        when(chatMemoryService.buildMemoryContext("u1", "conv-1", "问题", 600))
                .thenReturn("- [USER/PREFERENCE] 用户喜欢先给结论");

        String context = manager.buildPromptMemoryContext("u1", "conv-1", "问题", 1200);

        assertTrue(context.contains("短期运行记忆"));
        assertTrue(context.contains("search_knowledge 返回了 3 条片段"));
        assertTrue(context.contains("长期记忆"));
        assertTrue(context.contains("用户喜欢先给结论"));
    }

    @Test
    void recordsToolResultAsBoundedRuntimeMemoryEntry() {
        ConversationRuntimeMemory runtimeMemory = mock(ConversationRuntimeMemory.class);
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        ChatTokenEstimator estimator = mock(ChatTokenEstimator.class);
        LlmProviderRouter router = mock(LlmProviderRouter.class);
        ChatMemoryManager manager = new ChatMemoryManager(runtimeMemory, chatMemoryService, estimator, router);
        RuntimeMemoryEntry entry = new RuntimeMemoryEntry(
                "r1", "tool", "TOOL_RESULT", "工具结果", "tool_result", "search_knowledge", 10, "now");

        when(estimator.estimateTextTokens(any())).thenReturn(10);
        when(runtimeMemory.entry(eq("tool"), eq("TOOL_RESULT"), any(), eq("tool_result"), eq("search_knowledge"), eq(10)))
                .thenReturn(entry);
        when(runtimeMemory.list("conv-1")).thenReturn(List.of(entry));

        manager.rememberToolResult("u1", "conv-1", "search_knowledge", "工具结果");

        verify(runtimeMemory).append(eq("conv-1"), eq(entry), eq(80));
    }
}
