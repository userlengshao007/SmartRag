package com.zzzzyj.smartpai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzzzyj.smartpai.model.ChatMemory;
import com.zzzzyj.smartpai.model.User;
import com.zzzzyj.smartpai.repository.ChatMemoryRepository;
import com.zzzzyj.smartpai.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ChatMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(ChatMemoryService.class);
    private static final int DEFAULT_CANDIDATE_LIMIT = 200;
    private static final int EXPLICIT_MEMORY_MAX_CHARS = 1000;
    private static final Pattern EXPLICIT_MEMORY_PREFIX = Pattern.compile(
            "^(请)?(帮我)?记住[：:,，\\s]*(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {};

    private final ChatMemoryRepository chatMemoryRepository;
    private final UserRepository userRepository;
    private final ChatTokenEstimator chatTokenEstimator;
    private final ObjectMapper objectMapper;

    public ChatMemoryService(ChatMemoryRepository chatMemoryRepository,
                             UserRepository userRepository,
                             ChatTokenEstimator chatTokenEstimator,
                             ObjectMapper objectMapper) {
        this.chatMemoryRepository = chatMemoryRepository;
        this.userRepository = userRepository;
        this.chatTokenEstimator = chatTokenEstimator;
        this.objectMapper = objectMapper;
    }

    /**
     * 存储聊天记忆到数据库，支持用户级和会话级两种作用域。
     * 
     * 执行流程：
     * 1. 验证并解析用户ID和内容
     * 2. 规范化内容（去除多余空白）
     * 3. 检查是否存在重复记忆（相同用户、作用域、内容）
     * 4. 验证用户存在性
     * 5. 创建并保存记忆实体
     * 
     * 如果记忆已存在或参数无效，返回Optional.empty()而不是抛出异常。
     *
     * @param userId 用户ID字符串，会被解析为Long类型
     * @param conversationId 会话ID，仅在scope为CONVERSATION时使用
     * @param scope 记忆作用域（USER或CONVERSATION），为null时默认为USER
     * @param type 记忆类型（FACT/PREFERENCE/SUMMARY/TOOL_RESULT），为null时默认为FACT
     * @param content 记忆内容，不能为空或空白
     * @param metadata 元数据Map，会被序列化为JSON字符串存储
     * @return 保存成功的记忆对象，如果验证失败或记忆已存在则返回Optional.empty()
     */
    @Transactional
    public Optional<ChatMemory> storeMemory(String userId,
                                            String conversationId,
                                            ChatMemory.MemoryScope scope,
                                            ChatMemory.MemoryType type,
                                            String content,
                                            Map<String, String> metadata) {
        Long parsedUserId = parseUserId(userId);
        if (parsedUserId == null || content == null || content.isBlank()) {
            return Optional.empty();
        }

        String normalizedContent = normalizeMemoryContent(content);
        if (normalizedContent.isBlank()) {
            return Optional.empty();
        }

        // 根据作用域检查是否存在重复记忆
        ChatMemory.MemoryScope normalizedScope = scope == null ? ChatMemory.MemoryScope.USER : scope;
        Optional<ChatMemory> duplicate = normalizedScope == ChatMemory.MemoryScope.CONVERSATION
                ? chatMemoryRepository.findFirstByUserIdAndScopeAndConversationIdAndContent(
                        parsedUserId, normalizedScope, conversationId, normalizedContent)
                : chatMemoryRepository.findFirstByUserIdAndScopeAndContent(
                        parsedUserId, normalizedScope, normalizedContent);
        if (duplicate.isPresent()) {
            return duplicate;
        }

        User user = userRepository.findById(parsedUserId).orElse(null);
        if (user == null) {
            logger.warn("保存长期记忆失败：用户不存在 userId={}", userId);
            return Optional.empty();
        }

        // 构建记忆实体并设置所有字段
        ChatMemory memory = new ChatMemory();
        memory.setUser(user);
        memory.setScope(normalizedScope);
        memory.setConversationId(normalizedScope == ChatMemory.MemoryScope.CONVERSATION ? conversationId : null);
        memory.setOrgTag(user.getPrimaryOrg());
        memory.setType(type == null ? ChatMemory.MemoryType.FACT : type);
        memory.setContent(normalizedContent);
        memory.setMetadataJson(writeMetadata(metadata));
        memory.setTokenCount(chatTokenEstimator.estimateTextTokens(normalizedContent));
        return Optional.of(chatMemoryRepository.save(memory));
    }

    /**
     * 只处理用户明确说“记住……”的输入，避免把普通临时任务误写成长期事实。
     */
    @Transactional
    public Optional<ChatMemory> storeExplicitMemoryHint(String userId, String conversationId, String userMessage) {
        String content = extractExplicitMemoryContent(userMessage);
        if (content == null) {
            return Optional.empty();
        }
        return storeMemory(
                userId,
                conversationId,
                ChatMemory.MemoryScope.USER,
                inferType(content),
                content,
                Map.of("source", "explicit_user_hint", "requestId", UUID.randomUUID().toString())
        );
    }

    @Transactional(readOnly = true)
    public List<ChatMemory> retrieveRelevant(String userId,
                                             String conversationId,
                                             String query,
                                             int limit) {
        Long parsedUserId = parseUserId(userId);
        if (parsedUserId == null || query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }

        List<ChatMemory> candidates = chatMemoryRepository.findVisibleMemories(
                parsedUserId,
                conversationId,
                ChatMemory.MemoryScope.USER,
                ChatMemory.MemoryScope.CONVERSATION,
                PageRequest.of(0, DEFAULT_CANDIDATE_LIMIT)
        );
        Set<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        return candidates.stream()
                .map(memory -> new ScoredMemory(memory, score(memory, query, queryTokens)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed())
                .limit(limit)
                .map(ScoredMemory::memory)
                .toList();
    }

    @Transactional(readOnly = true)
    public String buildMemoryContext(String userId,
                                     String conversationId,
                                     String query,
                                     int maxTokens) {
        List<ChatMemory> memories = retrieveRelevant(userId, conversationId, query, 10);
        if (memories.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        int usedTokens = 0;
        for (ChatMemory memory : memories) {
            int nextTokens = memory.getTokenCount() == null
                    ? chatTokenEstimator.estimateTextTokens(memory.getContent())
                    : memory.getTokenCount();
            if (usedTokens + nextTokens > maxTokens) {
                break;
            }
            context.append("- [")
                    .append(memory.getScope())
                    .append("/")
                    .append(memory.getType())
                    .append("] ")
                    .append(memory.getContent())
                    .append("\n");
            usedTokens += nextTokens;
        }
        return context.toString().trim();
    }

    public Map<String, String> readMetadata(ChatMemory memory) {
        if (memory == null || memory.getMetadataJson() == null || memory.getMetadataJson().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(memory.getMetadataJson(), STRING_MAP_TYPE);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private String extractExplicitMemoryContent(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return null;
        }
        var matcher = EXPLICIT_MEMORY_PREFIX.matcher(userMessage.trim());
        if (!matcher.matches()) {
            return null;
        }
        String content = normalizeMemoryContent(matcher.group(3));
        if (content.length() < 4 || content.length() > EXPLICIT_MEMORY_MAX_CHARS) {
            return null;
        }
        return content;
    }

    private ChatMemory.MemoryType inferType(String content) {
        String normalized = content == null ? "" : content.toLowerCase(Locale.ROOT);
        if (normalized.contains("喜欢") || normalized.contains("偏好") || normalized.contains("习惯")
                || normalized.contains("以后") || normalized.contains("默认")) {
            return ChatMemory.MemoryType.PREFERENCE;
        }
        return ChatMemory.MemoryType.FACT;
    }

    private double score(ChatMemory memory, String rawQuery, Set<String> queryTokens) {
        String content = memory.getContent() == null ? "" : memory.getContent().toLowerCase(Locale.ROOT);
        String query = rawQuery == null ? "" : rawQuery.toLowerCase(Locale.ROOT).trim();
        // 用户偏好通常对多数后续问题都有效，给一个基础分，避免因为关键词不重合而完全丢失。
        double score = memory.getType() == ChatMemory.MemoryType.PREFERENCE ? 0.25d : 0.0d;
        if (content.contains(query)) {
            score += 2.0d;
        }

        Set<String> contentTokens = tokenize(content);
        int matched = 0;
        for (String token : queryTokens) {
            if (contentTokens.contains(token) || content.contains(token)) {
                matched++;
            }
        }
        if (matched > 0) {
            score += (double) matched / Math.max(queryTokens.size(), 1);
        }

        // 近期记忆给轻微加权，避免同分时太旧的偏好排在前面。
        LocalDateTime updatedAt = memory.getUpdatedAt() != null ? memory.getUpdatedAt() : memory.getCreatedAt();
        if (updatedAt != null) {
            long ageHours = Math.max(Duration.between(updatedAt, LocalDateTime.now()).toHours(), 0);
            score += Math.max(0.0d, 0.2d - ageHours / 240.0d);
        }
        return score;
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }

        String normalized = text.toLowerCase(Locale.ROOT);
        StringBuilder ascii = new StringBuilder();
        List<Character> cjkRun = new ArrayList<>();
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (isAsciiWordChar(ch)) {
                flushCjkRun(tokens, cjkRun);
                ascii.append(ch);
            } else if (isCjk(ch)) {
                flushAscii(tokens, ascii);
                cjkRun.add(ch);
            } else {
                flushAscii(tokens, ascii);
                flushCjkRun(tokens, cjkRun);
            }
        }
        flushAscii(tokens, ascii);
        flushCjkRun(tokens, cjkRun);
        return tokens;
    }

    private void flushAscii(Set<String> tokens, StringBuilder ascii) {
        if (ascii.length() >= 2) {
            tokens.add(ascii.toString());
        }
        ascii.setLength(0);
    }

    private void flushCjkRun(Set<String> tokens, List<Character> run) {
        if (run.isEmpty()) {
            return;
        }
        for (Character ch : run) {
            tokens.add(String.valueOf(ch));
        }
        for (int i = 0; i + 1 < run.size(); i++) {
            tokens.add("" + run.get(i) + run.get(i + 1));
        }
        run.clear();
    }

    private boolean isAsciiWordChar(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-';
    }

    private boolean isCjk(char ch) {
        Character.UnicodeScript script = Character.UnicodeScript.of(ch);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private String normalizeMemoryContent(String content) {
        return content == null ? "" : content.replaceAll("\\s+", " ").trim();
    }

    private String writeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(metadata));
        } catch (JsonProcessingException exception) {
            logger.warn("长期记忆 metadata 序列化失败，将跳过 metadata", exception);
            return null;
        }
    }

    private Long parseUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private record ScoredMemory(ChatMemory memory, double score) {
    }
}
