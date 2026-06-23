package com.zzzzyj.smartpai.service;

import com.zzzzyj.smartpai.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对 ReAct 循环里真正发送给模型的 messages 做上下文预算检查和压缩。
 *
 * 压缩只发生在 user message 边界：旧区间整体摘要,最近几个用户回合完整保留。
 * 这样可以避免切断 assistant tool_calls 与后续 tool 消息之间的协议配对。
 */
@Service
public class ReActContextCompressor {

    private static final Logger logger = LoggerFactory.getLogger(ReActContextCompressor.class);

    private final AiProperties aiProperties;
    private final ChatTokenEstimator chatTokenEstimator;
    private final LlmProviderRouter llmProviderRouter;

    /**
     * 构造函数，注入依赖的服务组件。
     *
     * @param aiProperties AI配置属性，用于获取token窗口、压缩阈值等参数
     * @param chatTokenEstimator Token估算器，用于计算消息列表的token数量
     * @param llmProviderRouter LLM提供者路由器，用于调用模型生成历史对话摘要
     */
    public ReActContextCompressor(AiProperties aiProperties,
                                  ChatTokenEstimator chatTokenEstimator,
                                  LlmProviderRouter llmProviderRouter) {
        this.aiProperties = aiProperties;
        this.chatTokenEstimator = chatTokenEstimator;
        this.llmProviderRouter = llmProviderRouter;
    }

    /**
     * 检查消息列表是否超过token阈值，如果超过则执行上下文压缩。
     * 
     * 压缩策略：
     * 1. 保留最近的N个用户回合（compressionRetainRecentRounds）
     * 2. 将早期的消息替换为LLM生成的摘要
     * 3. 在摘要后插入一条assistant确认消息，保持对话连贯性
     *
     * @param requesterId 请求者ID，用于路由到正确的LLM提供者
     * @param messages 待检查和可能压缩的消息列表，会被原地修改
     * @param tools 当前可用的工具列表，用于准确估算token
     * @return 压缩结果对象，包含压缩前后的token数、是否执行压缩等信息
     */
    public CompressionResult compressIfNeeded(String requesterId,
                                              List<Map<String, Object>> messages,
                                              List<AgentToolRegistry.AgentTool> tools) {
        if (messages == null || messages.isEmpty()) {
            return CompressionResult.notCompressed(0, 0, triggerTokens());
        }

        int beforeTokens = chatTokenEstimator.estimateReActRequestTokens(messages, tools);
        int trigger = triggerTokens();
        if (beforeTokens < trigger) {
            return CompressionResult.notCompressed(beforeTokens, beforeTokens, trigger);
        }

        int systemEnd = firstMessageIsSystem(messages) ? 1 : 0;
        List<Integer> userIndices = userMessageIndices(messages, systemEnd);
        int retainRecentRounds = retainRecentRounds();
        if (userIndices.size() <= retainRecentRounds) {
            logger.info("ReAct context compression skipped: userTurns={}, retainRecentRounds={}, tokens={}, trigger={}",
                    userIndices.size(), retainRecentRounds, beforeTokens, trigger);
            return CompressionResult.notCompressed(beforeTokens, beforeTokens, trigger);
        }

        // 计算分割点：保留最近N个用户回合，之前的都压缩
        int splitIndex = userIndices.get(userIndices.size() - retainRecentRounds);
        if (splitIndex <= systemEnd) {
            return CompressionResult.notCompressed(beforeTokens, beforeTokens, trigger);
        }

        // 分离早期消息和近期消息
        List<Map<String, Object>> oldMessages = copyMessages(messages.subList(systemEnd, splitIndex));
        List<Map<String, Object>> recentMessages = copyMessages(messages.subList(splitIndex, messages.size()));
        
        // 调用LLM生成早期消息的摘要
        String summary = summarize(requesterId, oldMessages);
        if (summary == null || summary.isBlank()) {
            logger.warn("ReAct context compression skipped: summary is blank");
            return CompressionResult.notCompressed(beforeTokens, beforeTokens, trigger);
        }

        // 重建消息列表：system消息 + 摘要 + 确认回复 + 近期消息
        List<Map<String, Object>> rebuilt = new ArrayList<>();
        if (systemEnd == 1) {
            rebuilt.add(new LinkedHashMap<>(messages.get(0)));
        }
        rebuilt.add(message("user", "[已压缩的历史对话摘要]\n" + summary.trim()));
        rebuilt.add(message("assistant", "好的，我已了解之前的上下文，请继续。"));
        rebuilt.addAll(recentMessages);

        messages.clear();
        messages.addAll(rebuilt);
        int afterTokens = chatTokenEstimator.estimateReActRequestTokens(messages, tools);
        logger.info("ReAct context compressed: tokens {} -> {}, messages {} -> {}, retainedUserTurns={}, trigger={}",
                beforeTokens, afterTokens, oldMessages.size() + recentMessages.size() + systemEnd,
                messages.size(), retainRecentRounds, trigger);
        return new CompressionResult(true, beforeTokens, afterTokens, trigger, summary.length());
    }

    /**
     * 调用LLM生成消息摘要，用于替换早期对话内容。
     *
     * @param requesterId 请求者ID，用于路由到正确的LLM提供者
     * @param oldMessages 需要被摘要的早期消息列表
     * @return 生成的摘要文本，如果摘要失败则返回null
     */
    private String summarize(String requesterId, List<Map<String, Object>> oldMessages) {
        try {
            return llmProviderRouter.summarizeMessagesForCompaction(
                    requesterId,
                    oldMessages,
                    summaryMaxTokens()
            );
        } catch (Exception exception) {
            logger.warn("ReAct context compression failed; continue with original messages", exception);
            return null;
        }
    }

    /**
     * 判断消息列表的第一条是否为system消息。
     *
     * @param messages 消息列表
     * @return 如果第一条消息的role为"system"则返回true，否则返回false
     */
    private boolean firstMessageIsSystem(List<Map<String, Object>> messages) {
        Object role = messages.get(0).get("role");
        return "system".equals(String.valueOf(role));
    }

    /**
     * 查找消息列表中所有user消息的索引位置。
     *
     * @param messages 消息列表
     * @param startIndex 开始搜索的起始索引（跳过system消息）
     * @return 所有user消息的索引列表
     */
    private List<Integer> userMessageIndices(List<Map<String, Object>> messages, int startIndex) {
        List<Integer> indices = new ArrayList<>();
        for (int i = startIndex; i < messages.size(); i++) {
            if ("user".equals(String.valueOf(messages.get(i).get("role")))) {
                indices.add(i);
            }
        }
        return indices;
    }

    /**
     * 深拷贝消息列表，避免修改原始数据。
     *
     * @param source 源消息列表
     * @return 复制后的新消息列表
     */
    private List<Map<String, Object>> copyMessages(List<Map<String, Object>> source) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> item : source) {
            copy.add(new LinkedHashMap<>(item));
        }
        return copy;
    }

    /**
     * 创建单条消息对象。
     *
     * @param role 消息角色（system/user/assistant）
     * @param content 消息内容
     * @return 包含role和content的Map对象
     */
    private Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content == null ? "" : content);
        return message;
    }

    /**
     * 计算触发压缩的token阈值。
     * 基于最大上下文token数乘以压缩触发比例，确保在达到模型限制前执行压缩。
     *
     * @return 触发压缩的token阈值
     */
    private int triggerTokens() {
        int window = Math.max(valueOrDefault(aiProperties.getGeneration().getMaxContextTokens(), 128000), 8000);
        double ratio = valueOrDefault(aiProperties.getGeneration().getCompressionTriggerRatio(), 0.9d);
        ratio = Math.max(0.1d, Math.min(ratio, 0.98d));
        return Math.max(1, (int) Math.floor(window * ratio));
    }

    /**
     * 获取压缩时保留的最近用户回合数。
     * 确保至少保留1个回合，默认值为3。
     *
     * @return 保留的最近用户回合数
     */
    private int retainRecentRounds() {
        return Math.max(valueOrDefault(aiProperties.getGeneration().getCompressionRetainRecentRounds(), 3), 1);
    }

    /**
     * 获取生成摘要时的最大token限制。
     * 确保至少128个token，默认值为800。
     *
     * @return 摘要的最大token数
     */
    private int summaryMaxTokens() {
        return Math.max(valueOrDefault(aiProperties.getGeneration().getCompressionSummaryMaxTokens(), 800), 128);
    }

    /**
     * 获取Integer配置值，如果为null则返回默认值。
     *
     * @param value 配置值
     * @param defaultValue 默认值
     * @return 实际使用的值
     */
    private int valueOrDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    /**
     * 获取Double配置值，如果为null则返回默认值。
     *
     * @param value 配置值
     * @param defaultValue 默认值
     * @return 实际使用的值
     */
    private double valueOrDefault(Double value, double defaultValue) {
        return value == null ? defaultValue : value;
    }

    /**
     * 压缩结果记录类，封装压缩操作的执行结果。
     *
     * @param compressed 是否执行了压缩
     * @param beforeTokens 压缩前的token数
     * @param afterTokens 压缩后的token数
     * @param triggerTokens 触发压缩的阈值
     * @param summaryChars 生成摘要的字符数
     */
    public record CompressionResult(
            boolean compressed,
            int beforeTokens,
            int afterTokens,
            int triggerTokens,
            int summaryChars
    ) {
        /**
         * 创建未执行压缩的结果对象。
         *
         * @param beforeTokens 压缩前的token数
         * @param afterTokens 压缩后的token数（通常与beforeTokens相同）
         * @param triggerTokens 触发压缩的阈值
         * @return 表示未压缩的CompressionResult对象
         */
        static CompressionResult notCompressed(int beforeTokens, int afterTokens, int triggerTokens) {
            return new CompressionResult(false, beforeTokens, afterTokens, triggerTokens, 0);
        }
    }
}
