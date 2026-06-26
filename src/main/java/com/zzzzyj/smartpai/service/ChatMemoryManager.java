package com.zzzzyj.smartpai.service;

import com.zzzzyj.smartpai.service.ConversationRuntimeMemory.RuntimeMemoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天记忆门面。
 *
 * 对 ChatHandler 暴露一个统一入口：短期运行记忆来自 Redis，长期事实/偏好来自 MySQL。
 * 这样 prompt 注入、短期压缩和长期检索不会散落在 ReAct 主循环里。
 */
@Service
public class ChatMemoryManager {

    private static final Logger logger = LoggerFactory.getLogger(ChatMemoryManager.class);

    private static final int RUNTIME_MEMORY_MAX_ENTRIES = 80;
    private static final int RUNTIME_MEMORY_MAX_TOKENS = 4_000;
    private static final int RUNTIME_MEMORY_RETAIN_RECENT_ENTRIES = 12;
    private static final int RUNTIME_CONTEXT_MAX_TOKENS = 1_000;
    private static final int RUNTIME_MAP_CHUNK_ENTRIES = 6;
    private static final int RUNTIME_MAP_SUMMARY_MAX_TOKENS = 300;
    private static final int RUNTIME_REDUCE_SUMMARY_MAX_TOKENS = 800;
    private static final int USER_MEMORY_MAX_CHARS = 1_000;
    private static final int ASSISTANT_MEMORY_MAX_CHARS = 1_200;
    private static final int TOOL_MEMORY_MAX_CHARS = 800;

    private final ConversationRuntimeMemory runtimeMemory;
    private final ChatMemoryService chatMemoryService;
    private final ChatTokenEstimator chatTokenEstimator;
    private final LlmProviderRouter llmProviderRouter;

    public ChatMemoryManager(ConversationRuntimeMemory runtimeMemory,
                             ChatMemoryService chatMemoryService,
                             ChatTokenEstimator chatTokenEstimator,
                             LlmProviderRouter llmProviderRouter) {
        this.runtimeMemory = runtimeMemory;
        this.chatMemoryService = chatMemoryService;
        this.chatTokenEstimator = chatTokenEstimator;
        this.llmProviderRouter = llmProviderRouter;
    }

    public String buildPromptMemoryContext(String userId,
                                           String conversationId,
                                           String query,
                                           int maxTokens) {
        int runtimeBudget = Math.min(RUNTIME_CONTEXT_MAX_TOKENS, Math.max(300, maxTokens / 2));
        int longTermBudget = Math.max(300, maxTokens - runtimeBudget);
        String runtimeContext = buildRuntimeContext(conversationId, runtimeBudget);
        String longTermContext = chatMemoryService.buildMemoryContext(userId, conversationId, query, longTermBudget);

        StringBuilder context = new StringBuilder();
        if (!runtimeContext.isBlank()) {
            context.append("短期运行记忆：\n").append(runtimeContext).append("\n\n");
        }
        if (longTermContext != null && !longTermContext.isBlank()) {
            context.append("长期记忆：\n").append(longTermContext.trim());
        }
        return context.toString().trim();
    }

    public void rememberUserMessage(String userId, String conversationId, String content) {
        appendAndCompact(userId, conversationId, "user", "CONVERSATION", content, "user_message", null, USER_MEMORY_MAX_CHARS);
    }

    public void rememberAssistantMessage(String userId, String conversationId, String content) {
        appendAndCompact(userId, conversationId, "assistant", "CONVERSATION", content, "assistant_final", null, ASSISTANT_MEMORY_MAX_CHARS);
    }

    public void rememberToolResult(String userId, String conversationId, String toolName, String result) {
        appendAndCompact(userId, conversationId, "tool", "TOOL_RESULT", result, "tool_result", toolName, TOOL_MEMORY_MAX_CHARS);
    }

    private void appendAndCompact(String userId,
                                  String conversationId,
                                  String role,
                                  String type,
                                  String content,
                                  String source,
                                  String toolName,
                                  int maxChars) {
        if (conversationId == null || conversationId.isBlank() || content == null || content.isBlank()) {
            return;
        }
        String compactContent = limitText(content, maxChars);
        RuntimeMemoryEntry entry = runtimeMemory.entry(
                role,
                type,
                compactContent,
                source,
                toolName,
                chatTokenEstimator.estimateTextTokens(compactContent)
        );
        runtimeMemory.append(conversationId, entry, RUNTIME_MEMORY_MAX_ENTRIES);
        compactRuntimeMemoryIfNeeded(userId, conversationId);
    }

    private void compactRuntimeMemoryIfNeeded(String userId, String conversationId) {
        List<RuntimeMemoryEntry> entries = runtimeMemory.list(conversationId);
        int totalTokens = entries.stream().mapToInt(this::entryTokens).sum();
        if (totalTokens < RUNTIME_MEMORY_MAX_TOKENS || entries.size() <= RUNTIME_MEMORY_RETAIN_RECENT_ENTRIES) {
            return;
        }

        int splitIndex = Math.max(0, entries.size() - RUNTIME_MEMORY_RETAIN_RECENT_ENTRIES);
        List<RuntimeMemoryEntry> oldEntries = new ArrayList<>(entries.subList(0, splitIndex));
        List<RuntimeMemoryEntry> recentEntries = new ArrayList<>(entries.subList(splitIndex, entries.size()));

        String summary = summarizeRuntimeEntries(userId, oldEntries);
        if (summary == null || summary.isBlank()) {
            // 摘要失败时宁可只保留最近运行态，也不要让 Redis 短期记忆无限增长压垮 prompt。
            logger.warn("短期运行记忆压缩失败，将仅保留最近条目: conversationId={}", conversationId);
            runtimeMemory.replace(conversationId, recentEntries);
            return;
        }

        RuntimeMemoryEntry summaryEntry = runtimeMemory.entry(
                "system",
                "SUMMARY",
                "[短期运行记忆摘要] " + summary.trim(),
                "runtime_compaction",
                null,
                chatTokenEstimator.estimateTextTokens(summary)
        );
        List<RuntimeMemoryEntry> rebuilt = new ArrayList<>();
        rebuilt.add(summaryEntry);
        rebuilt.addAll(recentEntries);
        runtimeMemory.replace(conversationId, rebuilt);
        logger.info("短期运行记忆已压缩: conversationId={}, tokens {} -> {}, entries {} -> {}",
                conversationId, totalTokens, rebuilt.stream().mapToInt(this::entryTokens).sum(), entries.size(), rebuilt.size());
    }

    private String summarizeRuntimeEntries(String userId, List<RuntimeMemoryEntry> entries) {
        try {
            List<String> chunkSummaries = new ArrayList<>();
            for (List<RuntimeMemoryEntry> chunk : partition(entries, RUNTIME_MAP_CHUNK_ENTRIES)) {
                String summary = llmProviderRouter.summarizeMessagesForCompaction(
                        userId,
                        toSummaryMessages(chunk),
                        RUNTIME_MAP_SUMMARY_MAX_TOKENS
                );
                if (summary == null || summary.isBlank()) {
                    return null;
                }
                chunkSummaries.add(summary.trim());
            }
            if (chunkSummaries.isEmpty()) {
                return null;
            }
            if (chunkSummaries.size() == 1) {
                return chunkSummaries.get(0);
            }
            return llmProviderRouter.mergeCompactionSummaries(
                    userId,
                    chunkSummaries,
                    RUNTIME_REDUCE_SUMMARY_MAX_TOKENS
            );
        } catch (Exception exception) {
            logger.warn("短期运行记忆 Map-Reduce 摘要失败: userId={}", userId, exception);
            return null;
        }
    }

    private String buildRuntimeContext(String conversationId, int maxTokens) {
        List<RuntimeMemoryEntry> entries = runtimeMemory.list(conversationId);
        if (entries.isEmpty()) {
            return "";
        }

        List<RuntimeMemoryEntry> selected = new ArrayList<>();
        int usedTokens = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            RuntimeMemoryEntry entry = entries.get(i);
            int nextTokens = entryTokens(entry);
            if (usedTokens + nextTokens > maxTokens) {
                break;
            }
            selected.add(entry);
            usedTokens += nextTokens;
        }
        Collections.reverse(selected);

        StringBuilder context = new StringBuilder();
        for (RuntimeMemoryEntry entry : selected) {
            context.append("- [")
                    .append(entry.type())
                    .append("/")
                    .append(entry.role());
            if (entry.toolName() != null && !entry.toolName().isBlank()) {
                context.append("/").append(entry.toolName());
            }
            context.append("] ")
                    .append(entry.content())
                    .append("\n");
        }
        return context.toString().trim();
    }

    private List<Map<String, Object>> toSummaryMessages(List<RuntimeMemoryEntry> entries) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (RuntimeMemoryEntry entry : entries) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", normalizeRole(entry.role()));
            message.put("content", "[" + entry.type() + "] " + entry.content());
            messages.add(message);
        }
        return messages;
    }

    private String normalizeRole(String role) {
        if ("user".equals(role) || "assistant".equals(role) || "tool".equals(role) || "system".equals(role)) {
            return role;
        }
        return "user";
    }

    private List<List<RuntimeMemoryEntry>> partition(List<RuntimeMemoryEntry> entries, int chunkSize) {
        List<List<RuntimeMemoryEntry>> chunks = new ArrayList<>();
        int safeChunkSize = Math.max(chunkSize, 1);
        for (int i = 0; i < entries.size(); i += safeChunkSize) {
            chunks.add(entries.subList(i, Math.min(i + safeChunkSize, entries.size())));
        }
        return chunks;
    }

    private int entryTokens(RuntimeMemoryEntry entry) {
        if (entry == null) {
            return 0;
        }
        return entry.tokenCount() > 0 ? entry.tokenCount() : chatTokenEstimator.estimateTextTokens(entry.content());
    }

    private String limitText(String text, int maxChars) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(maxChars, 0)) + "...(已截断)";
    }
}
