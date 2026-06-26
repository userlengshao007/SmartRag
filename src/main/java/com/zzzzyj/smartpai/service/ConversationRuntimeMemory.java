package com.zzzzyj.smartpai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 会话级运行时短期记忆。
 *
 * 这里存的是“给后续轮次复用的摘要/截断版”，不是完整工具原文。完整工具结果只留在本轮
 * ReAct messages 里供模型继续推理，避免把权限数据、超长片段或一次性噪声长期塞进 Redis。
 */
@Service
public class ConversationRuntimeMemory {

    private static final Logger logger = LoggerFactory.getLogger(ConversationRuntimeMemory.class);
    private static final Duration RUNTIME_MEMORY_TTL = Duration.ofDays(7);
    private static final TypeReference<List<RuntimeMemoryEntry>> ENTRY_LIST_TYPE = new TypeReference<>() {};

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public ConversationRuntimeMemory(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public List<RuntimeMemoryEntry> list(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        String json = redisTemplate.opsForValue().get(key(conversationId));
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, ENTRY_LIST_TYPE);
        } catch (Exception exception) {
            logger.warn("读取会话运行时记忆失败，将忽略旧记忆: conversationId={}", conversationId, exception);
            return List.of();
        }
    }

    public void append(String conversationId, RuntimeMemoryEntry entry, int maxEntries) {
        if (conversationId == null || conversationId.isBlank() || entry == null) {
            return;
        }
        List<RuntimeMemoryEntry> entries = new ArrayList<>(list(conversationId));
        entries.add(entry);
        int safeMaxEntries = Math.max(maxEntries, 1);
        if (entries.size() > safeMaxEntries) {
            entries = new ArrayList<>(entries.subList(entries.size() - safeMaxEntries, entries.size()));
        }
        replace(conversationId, entries);
    }

    public void replace(String conversationId, List<RuntimeMemoryEntry> entries) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    key(conversationId),
                    objectMapper.writeValueAsString(entries == null ? List.of() : entries),
                    RUNTIME_MEMORY_TTL
            );
        } catch (JsonProcessingException exception) {
            logger.warn("保存会话运行时记忆失败: conversationId={}", conversationId, exception);
        }
    }

    public RuntimeMemoryEntry entry(String role,
                                    String type,
                                    String content,
                                    String source,
                                    String toolName,
                                    int tokenCount) {
        return new RuntimeMemoryEntry(
                UUID.randomUUID().toString(),
                role == null ? "unknown" : role,
                type == null ? "CONVERSATION" : type,
                content == null ? "" : content,
                source == null ? "runtime" : source,
                toolName,
                Math.max(tokenCount, 0),
                LocalDateTime.now().toString()
        );
    }

    private String key(String conversationId) {
        return "conversation:" + conversationId + ":runtime_memory";
    }

    public record RuntimeMemoryEntry(
            String id,
            String role,
            String type,
            String content,
            String source,
            String toolName,
            int tokenCount,
            String createdAt
    ) {
    }
}
