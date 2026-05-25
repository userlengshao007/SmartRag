package com.zzzzyj.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理用户的 WebSocket 连接
 */
@Component
public class ChatSessionRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ChatSessionRegistry.class);

    // key: userId value: websocketSeessions（Spring WebSocket 的会话对象）
    // 用 ConcurrentHashMap 是因为多个线程可能同时读写（ReAct 循环在另一个线程里执行，但发消息走主 WebSocket 线程）。
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public ChatSessionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void registerSession(String userId, WebSocketSession session) {
        sessions.put(userId, session);
    }

    public void unregisterSession(String userId, WebSocketSession session) {
        sessions.computeIfPresent(userId, (key, current) -> current == session ? null : current);
    }

    public WebSocketSession getCurrentSession(String userId) {
        return sessions.get(userId);
    }

    /**
     * 发送消息给用户
     * ChatHandler 调的所有 send* 方法（sendGenerationStart、sendResponseChunk 等）最终都调这个。
     *
     * @param userId
     * @param payload
     */
    public void sendJsonToUser(String userId, Map<String, ?> payload) {
        WebSocketSession session = sessions.get(userId);
        if (session == null || !session.isOpen()) {
            logger.debug("用户 {} 当前没有可用的 WebSocket 会话，跳过发送", userId);
            return;
        }

        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
                }
            }
        } catch (Exception e) {
            logger.error("向用户 {} 发送 WebSocket 消息失败: {}", userId, e.getMessage(), e);
        }
    }
}
