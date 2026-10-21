package com.zzzzyj.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 重排序配置；候选数量与最终返回条数 topK 分开控制。 */
@Data
@Component
@ConfigurationProperties(prefix = "search.rerank")
public class RerankProperties {
    /** 是否启用模型重排序。 */
    private boolean enabled = true;
    /** 百炼文本排序接口地址。 */
    private String url = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
    /** 排序模型名称。 */
    private String model = "gte-rerank-v2";
    /** 留空时复用当前激活的阿里云 embedding Key。 */
    private String apiKey = "";
    /** 送入排序模型的候选上限。 */
    private int maxCandidates = 50;
    /** 最低相关性分数；低分结果不补齐。 */
    private double scoreThreshold = 0.1;
    /** 每条候选发送的最大字符数。 */
    private int maxDocumentChars = 2000;
    /** 调用超时秒数。 */
    private int timeoutSeconds = 10;
}
