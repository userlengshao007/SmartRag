package com.zzzzyj.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 全局 AI 相关配置，包含 Prompt 模板和生成参数。
 */
@Component
@ConfigurationProperties(prefix = "ai")
@Data
public class AiProperties {

    private Prompt prompt = new Prompt();
    private Generation generation = new Generation();

    @Data
    public static class Prompt {
        /** 规则文案 */
        private String rules;
        /** 引用开始分隔符 */
        private String refStart;
        /** 引用结束分隔符 */
        private String refEnd;
        /** 无检索结果时的占位文案 */
        private String noResultText;
    }

    @Data
    public static class Generation {
        /** 采样温度 */
        private Double temperature = 0.3;
        /** 最大输出 tokens */
        private Integer maxTokens = 2000;
        /** nucleus top-p */
        private Double topP = 0.9;
        /** 模型上下文窗口大小，用于触发历史压缩；不同 provider 可通过环境变量覆盖。 */
        private Integer maxContextTokens = 128000;
        /** 上下文占用达到该比例时触发压缩。 */
        private Double compressionTriggerRatio = 0.9;
        /** 压缩时保留最近 N 个用户回合，避免把当前任务和最近工具结果摘要掉。 */
        private Integer compressionRetainRecentRounds = 3;
        /** 摘要请求的最大输出 tokens。 */
        private Integer compressionSummaryMaxTokens = 800;
        /** Map-Reduce 压缩时每个 map 分片包含的消息数，过大容易让单次摘要请求先超窗。 */
        private Integer compressionMapChunkMessages = 5;
        /** Map 阶段每个分片摘要的最大输出 tokens。 */
        private Integer compressionMapSummaryMaxTokens = 300;
        /** Reduce 阶段合并摘要的最大输出 tokens。 */
        private Integer compressionReduceSummaryMaxTokens = 800;
    }
}
