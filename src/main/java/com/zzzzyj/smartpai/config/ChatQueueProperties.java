package com.zzzzyj.smartpai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 分布式并发队列限流配置。
 * 对应 application.yml 中 chat.queue.* 前缀。
 */
@Component
@ConfigurationProperties(prefix = "chat.queue")
public class ChatQueueProperties {

    /** 允许同时进行 LLM 调用的最大并发数（即信号量许可数） */
    private int maxConcurrent = 5;

    /** 请求在队列中等待许可的最长秒数，超时后向客户端返回提示 */
    private int maxWaitSeconds = 30;

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public void setMaxConcurrent(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
    }

    public int getMaxWaitSeconds() {
        return maxWaitSeconds;
    }

    public void setMaxWaitSeconds(int maxWaitSeconds) {
        this.maxWaitSeconds = maxWaitSeconds;
    }
}
