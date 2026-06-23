package com.zzzzyj.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 统一估算聊天请求里的 token 占用。
 *
 * 这里仍然是近似值，不追求和具体模型 tokenizer 完全一致；它的职责是给上下文预算、
 * 额度预留和压缩触发提供稳定、保守的判断依据。
 */
@Service
public class ChatTokenEstimator {

    private final UsageQuotaService usageQuotaService;
    private final ObjectMapper objectMapper;

    public ChatTokenEstimator(UsageQuotaService usageQuotaService, ObjectMapper objectMapper) {
        this.usageQuotaService = usageQuotaService;
        this.objectMapper = objectMapper;
    }

    public int estimateTextTokens(String text) {
        return usageQuotaService.estimateTextTokens(text);
    }

    public int estimateMessagesTokens(List<? extends Map<String, ?>> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int tokens = 0;
        for (Map<String, ?> message : messages) {
            tokens += estimateMessageTokens(message);
        }
        return Math.max(tokens, 1);
    }

    public int estimateMessageTokens(Map<String, ?> message) {
        if (message == null || message.isEmpty()) {
            return 0;
        }

        // role/content 分隔符、JSON envelope 等结构开销，按略偏保守的常量估算。
        int tokens = 8;
        tokens += estimateTextTokens(stringValue(message.get("role")));
        tokens += estimateTextTokens(stringValue(message.get("content")));

        Object reasoningContent = message.get("reasoning_content");
        if (reasoningContent != null) {
            tokens += estimateTextTokens(String.valueOf(reasoningContent));
        }

        Object toolCalls = message.get("tool_calls");
        if (toolCalls != null) {
            tokens += estimateJsonishTokens(toolCalls, 128);
        }

        Object toolCallId = message.get("tool_call_id");
        if (toolCallId != null) {
            tokens += estimateTextTokens(String.valueOf(toolCallId));
        }
        return tokens;
    }

    public int estimateToolsTokens(List<AgentToolRegistry.AgentTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        int tokens = 0;
        for (AgentToolRegistry.AgentTool tool : tools) {
            tokens += estimateTextTokens(tool.name());
            tokens += estimateTextTokens(tool.description());
            tokens += estimateJsonishTokens(tool.parameters(), 80);
        }
        return Math.max(tokens, 1);
    }

    public int estimateReActRequestTokens(List<Map<String, Object>> messages,
                                          List<AgentToolRegistry.AgentTool> tools) {
        return estimateMessagesTokens(messages) + estimateToolsTokens(tools);
    }

    private int estimateJsonishTokens(Object value, int fallbackTokens) {
        try {
            return estimateTextTokens(objectMapper.writeValueAsString(value));
        } catch (Exception ignored) {
            return fallbackTokens;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
