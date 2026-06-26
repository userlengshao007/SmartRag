package com.zzzzyj.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * ReAct 循环的轻量保险阀。
 *
 * 轮次和工具总数只能限制“跑多久”，这里额外检测连续相同工具签名，
 * 避免模型在同一个工具名和同一组参数上重复打转。
 */
public class ReActBudgetGuard {

    private static final Logger logger = LoggerFactory.getLogger(ReActBudgetGuard.class);

    private final int stagnationWindow;
    private final ObjectMapper objectMapper;
    private final Deque<String> recentToolSignatures = new ArrayDeque<>();

    public ReActBudgetGuard(int stagnationWindow, ObjectMapper objectMapper) {
        this.stagnationWindow = Math.max(stagnationWindow, 2);
        this.objectMapper = objectMapper;
    }

    public boolean recordAndIsStagnant(List<LlmProviderRouter.ToolCallDecision> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            recentToolSignatures.clear();
            return false;
        }

        String signature = signatureOf(toolCalls);
        recentToolSignatures.addLast(signature);
        while (recentToolSignatures.size() > stagnationWindow) {
            recentToolSignatures.removeFirst();
        }
        if (recentToolSignatures.size() < stagnationWindow) {
            return false;
        }

        String first = recentToolSignatures.peekFirst();
        boolean stagnant = recentToolSignatures.stream().allMatch(first::equals);
        if (stagnant) {
            logger.warn("ReAct stagnation detected: repeatedToolSignature={}, window={}", signature, stagnationWindow);
        }
        return stagnant;
    }

    private String signatureOf(List<LlmProviderRouter.ToolCallDecision> toolCalls) {
        StringBuilder signature = new StringBuilder();
        for (LlmProviderRouter.ToolCallDecision call : toolCalls) {
            signature.append(call.name()).append('|').append(argumentsJson(call)).append(';');
        }
        return signature.toString();
    }

    private String argumentsJson(LlmProviderRouter.ToolCallDecision call) {
        try {
            return objectMapper.writeValueAsString(call.arguments());
        } catch (Exception ignored) {
            return String.valueOf(call.arguments());
        }
    }
}
