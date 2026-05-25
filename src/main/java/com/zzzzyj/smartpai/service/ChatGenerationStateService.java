package com.zzzzyj.smartpai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ChatGenerationStateService {

    private static final Logger logger = LoggerFactory.getLogger(ChatGenerationStateService.class);
    private static final Duration GENERATION_TTL = Duration.ofMinutes(30);
    private static final TypeReference<Map<String, Map<String, Object>>> REFERENCE_MAP_TYPE =
            new TypeReference<Map<String, Map<String, Object>>>() {};

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public ChatGenerationStateService(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建新的生成任务
     *
     * @param userId
     * @param conversationId
     * @param question
     * @return
     */
    public GenerationSnapshot createGeneration(String userId, String conversationId, String question) {
        // 生成唯一 UUID 作为 generationId
        String generationId = UUID.randomUUID().toString();
        // 创建时间戳
        String now = LocalDateTime.now().toString();
        // 构造元数据对象，状态是 STREAMING(生成中，正在流式输出)
        GenerationMeta meta = new GenerationMeta(generationId, userId, conversationId, question, GenerationStatus.STREAMING, now, now, null);

        // 写入顺序：先把可读子项准备好，最后再发布 active key，避免读取者拿到 active key 后却查不到 meta/content。
        // 如果先写 activeGenerationKey，用户立刻查询，会发现 getGeneration 找不到数据（因为 meta 还没写）。所以先把子项（content、meta）写好，最后再暴露 active key
        redisTemplate.delete(referenceKey(generationId)); // 先删ref
        redisTemplate.opsForValue().set(contentKey(generationId), "", GENERATION_TTL); // content 设置为空内容
        writeMeta(meta); // 把元数据写入
        redisTemplate.opsForValue().set(activeGenerationKey(userId), generationId, GENERATION_TTL); // 把当前generationKey写入
        // 返回快照
        return toSnapshot(meta, "", Collections.emptyMap());
    }

    /**
     * 追加文本生成片段
     * 在流式生成过程中，把 LLM 返回的一个文本片段追加到 content 里
     *
     * @param generationId
     * @param chunk
     */
    public void appendChunk(String generationId, String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            touch(generationId); // // 空 chunk 只更新时间戳
            return;
        }

        // 用 Redis 的 APPEND 命令追加内容（原子操作）
        redisTemplate.opsForValue().append(contentKey(generationId), chunk);
        touch(generationId); // 更新时间戳，续命 TTL
    }

    /**
     * 更新引用映射
     * 把搜索结果的引用编号 [N] → 文件信息 保存下来
     *
     * @param generationId
     * @param referenceMappings
     */
    public void updateReferenceMappings(String generationId, Map<String, Map<String, Object>> referenceMappings) {
        try {
            if (referenceMappings == null || referenceMappings.isEmpty()) {
                redisTemplate.delete(referenceKey(generationId)); // 空就删掉
            } else {
                redisTemplate.opsForValue().set(
                        referenceKey(generationId),
                        objectMapper.writeValueAsString(referenceMappings),
                        GENERATION_TTL
                );
            }
            touch(generationId); // 续命
        } catch (Exception e) {
            logger.warn("保存生成态引用映射失败: generationId={}", generationId, e);
        }
    }

    /**
     * 状态机的最终态切换
     *
     * @param generationId
     * @param referenceMappings
     */
    public void markCompleted(String generationId, Map<String, Map<String, Object>> referenceMappings) {
        updateTerminalState(generationId, GenerationStatus.COMPLETED, null, referenceMappings);
    }

    public void markFailed(String generationId, String errorMessage) {
        updateTerminalState(generationId, GenerationStatus.FAILED, errorMessage, null);
    }

    public void markCancelled(String generationId) {
        updateTerminalState(generationId, GenerationStatus.CANCELLED, null, null);
    }

    public Optional<GenerationSnapshot> getGeneration(String generationId) {
        GenerationMeta meta = readMeta(generationId);
        if (meta == null) {
            return Optional.empty();
        }

        String content = Optional.ofNullable(redisTemplate.opsForValue().get(contentKey(generationId))).orElse("");
        Map<String, Map<String, Object>> references = readReferenceMappings(generationId);
        return Optional.of(toSnapshot(meta, content, references));
    }

    public Optional<GenerationSnapshot> getGenerationForUser(String generationId, String userId) {
        return getGeneration(generationId).filter(snapshot -> snapshot.userId().equals(userId));
    }

    public Optional<GenerationSnapshot> getActiveGenerationForUser(String userId) {
        String generationId = redisTemplate.opsForValue().get(activeGenerationKey(userId));
        if (generationId == null || generationId.isBlank()) {
            return Optional.empty();
        }
        return getGenerationForUser(generationId, userId);
    }

    /**
     * 切换到终态
     * 把生成任务标记为完成/失败/取消
     *
     * @param generationId
     * @param status
     * @param errorMessage
     * @param referenceMappings
     */
    private void updateTerminalState(String generationId,
                                     GenerationStatus status,
                                     String errorMessage,
                                     Map<String, Map<String, Object>> referenceMappings) {
        GenerationMeta meta = readMeta(generationId);
        // 1. 读取现有 meta
        if (meta == null) {
            return;
        }

        // 2. 如果有引用映射就更新，否则只续命
        if (referenceMappings != null) {
            updateReferenceMappings(generationId, referenceMappings);
        } else {
            touch(generationId);
        }

        // 3. 构造新的 meta，把状态换成终态
        String now = LocalDateTime.now().toString();
        GenerationMeta updated = new GenerationMeta(
                meta.generationId(),
                meta.userId(),
                meta.conversationId(),
                meta.question(),
                status,
                meta.createdAt(),
                now,
                errorMessage
        );
        writeMeta(updated);
        // 4. 清理用户当前活动生成（不再允许通过 active key 查询到这个已结束的任务）
        clearActiveGeneration(meta.userId(), generationId);
    }

    private void touch(String generationId) {
        GenerationMeta meta = readMeta(generationId);
        if (meta == null) {
            return;
        }

        GenerationMeta updated = new GenerationMeta(
                meta.generationId(),
                meta.userId(),
                meta.conversationId(),
                meta.question(),
                meta.status(),
                meta.createdAt(),
                LocalDateTime.now().toString(),
                meta.errorMessage()
        );
        writeMeta(updated);
        redisTemplate.expire(contentKey(generationId), GENERATION_TTL);
        redisTemplate.expire(referenceKey(generationId), GENERATION_TTL);
        redisTemplate.opsForValue().set(activeGenerationKey(meta.userId()), generationId, GENERATION_TTL);
    }

    private void clearActiveGeneration(String userId, String generationId) {
        String current = redisTemplate.opsForValue().get(activeGenerationKey(userId));
        if (generationId.equals(current)) {
            redisTemplate.delete(activeGenerationKey(userId));
        }
    }

    private void writeMeta(GenerationMeta meta) {
        try {
            redisTemplate.opsForValue().set(metaKey(meta.generationId()), objectMapper.writeValueAsString(meta), GENERATION_TTL);
        } catch (Exception e) {
            throw new IllegalStateException("保存生成态元数据失败", e);
        }
    }

    private GenerationMeta readMeta(String generationId) {
        String raw = redisTemplate.opsForValue().get(metaKey(generationId));
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return objectMapper.readValue(raw, GenerationMeta.class);
        } catch (Exception e) {
            logger.warn("解析生成态元数据失败: generationId={}", generationId, e);
            return null;
        }
    }

    private Map<String, Map<String, Object>> readReferenceMappings(String generationId) {
        String raw = redisTemplate.opsForValue().get(referenceKey(generationId));
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }

        try {
            return objectMapper.readValue(raw, REFERENCE_MAP_TYPE);
        } catch (Exception e) {
            logger.warn("解析生成态引用映射失败: generationId={}", generationId, e);
            return Collections.emptyMap();
        }
    }

    private GenerationSnapshot toSnapshot(GenerationMeta meta,
                                          String content,
                                          Map<String, Map<String, Object>> referenceMappings) {
        return new GenerationSnapshot(
                meta.generationId(),
                meta.userId(),
                meta.conversationId(),
                meta.question(),
                meta.status(),
                content,
                meta.createdAt(),
                meta.updatedAt(),
                meta.errorMessage(),
                referenceMappings == null ? Collections.emptyMap() : referenceMappings
        );
    }

    private String metaKey(String generationId) {
        return "chat:generation:" + generationId + ":meta";
    }

    private String contentKey(String generationId) {
        return "chat:generation:" + generationId + ":content";
    }

    private String referenceKey(String generationId) {
        return "chat:generation:" + generationId + ":refs";
    }

    private String activeGenerationKey(String userId) {
        return "chat:user:" + userId + ":active_generation";
    }

    public enum GenerationStatus {
        STREAMING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public record GenerationMeta(
            String generationId,
            String userId,
            String conversationId,
            String question,
            GenerationStatus status,
            String createdAt,
            String updatedAt,
            String errorMessage
    ) {
    }

    public record GenerationSnapshot(
            String generationId,
            String userId,
            String conversationId,
            String question,
            GenerationStatus status,
            String content,
            String createdAt,
            String updatedAt,
            String errorMessage,
            Map<String, Map<String, Object>> referenceMappings
    ) {
    }
}
