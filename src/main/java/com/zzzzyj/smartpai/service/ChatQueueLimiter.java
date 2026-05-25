package com.zzzzyj.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzzzyj.smartpai.config.ChatQueueProperties;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RScript;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 分布式并发队列限流器。
 *
 * 核心机制：
 *   1. 所有请求入队 ZSET（score = 自增序列），实现公平排队。
 *   2. Lua 脚本原子操作：检查排名 + 出队，防止超卖。
 *   3. Redisson 信号量控制最大并发数，许可带租约防死锁。
 *   4. 轮询检查 + Pub/Sub 唤醒，及时响应许可释放。
 *   5. 超时/取消时从 ZSET 移除，通过 WebSocket 通知客户端。
 */
@Service
public class ChatQueueLimiter {

    private static final Logger log = LoggerFactory.getLogger(ChatQueueLimiter.class);

    // Redis Key
    private static final String SEMAPHORE_KEY = "chat:semaphore";
    private static final String QUEUE_KEY = "chat:queue";
    private static final String QUEUE_SEQ_KEY = "chat:queue:seq";
    private static final String NOTIFY_TOPIC = "chat:queue:notify";
    private static final String CLAIM_LUA_PATH = "lua/queue_claim_atomic.lua";

    private final RedissonClient redisson;
    private final ChatQueueProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String claimLuaScript;

    // 轮询调度器
    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(
            2,
            r -> {
                Thread t = new Thread(r);
                t.setName("chat-queue-scheduler");
                t.setDaemon(true);
                return t;
            }
    );

    // Pub/Sub 监听器 ID
    private volatile int notifyListenerId = -1;
    // 等待中的请求，用于 Pub/Sub 唤醒
    private final ConcurrentHashMap<String, Runnable> waitingPollers = new ConcurrentHashMap<>();

    public ChatQueueLimiter(RedissonClient redisson, ChatQueueProperties props) {
        this.redisson = redisson;
        this.props = props;
        this.claimLuaScript = loadLuaScript();
    }

    @PostConstruct
    public void init() {
        // 初始化信号量
        RPermitExpirableSemaphore semaphore = redisson.getPermitExpirableSemaphore(SEMAPHORE_KEY);
        semaphore.trySetPermits(props.getMaxConcurrent());
        log.info("[ChatQueue] 信号量初始化完成，maxConcurrent={}", props.getMaxConcurrent());

        // 订阅 Pub/Sub，用于唤醒等待的请求
        RTopic topic = redisson.getTopic(NOTIFY_TOPIC);
        notifyListenerId = topic.addListener(String.class, (channel, msg) -> {
            // 唤醒所有等待的轮询任务
            for (Runnable poller : waitingPollers.values()) {
                try {
                    poller.run();
                } catch (Exception e) {
                    log.debug("[ChatQueue] 唤醒轮询任务异常: {}", e.getMessage());
                }
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        if (notifyListenerId != -1) {
            redisson.getTopic(NOTIFY_TOPIC).removeListener(notifyListenerId);
        }
        scheduler.shutdown();
        waitingPollers.clear();
    }

    /**
     * 将请求加入队列并等待许可。
     *
     * @param conversationId 对话 ID（作为 ZSET member）
     * @param userId  当前用户 ID
     * @param payload 聊天消息原文
     * @param session WebSocket 会话（用于发送状态/错误消息）
     * @param chatHandler 实际执行 LLM 请求的 handler
     */
    public void enqueue(String conversationId, String userId, String payload, WebSocketSession session, ChatHandler chatHandler) {
        long seq = nextQueueSeq();
        RScoredSortedSet<String> queue = redisson.getScoredSortedSet(QUEUE_KEY, StringCodec.INSTANCE);

        // 所有请求都入 ZSET，member = conversationId，score = 自增序列号
        queue.add(seq, conversationId);
        log.info("[ChatQueue] 对话 {} 入队，用户={}，序列={}", conversationId, userId, seq);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<String> permitRef = new AtomicReference<>();

        // 释放资源的回调（只执行一次）
        Runnable releaseOnce = () -> {
            cancelled.set(true);
            queue.remove(conversationId);
            waitingPollers.remove(conversationId);
            String permitId = permitRef.getAndSet(null);
            if (permitId != null) {
                try {
                    redisson.getPermitExpirableSemaphore(SEMAPHORE_KEY).release(permitId);
                    publishNotify();
                } catch (Exception e) {
                    log.error("[ChatQueue] 释放许可异常: {}", e.getMessage());
                }
            }
        };

        // WebSocket 连接关闭时的清理
        // 注意：session 的关闭回调需要在 WebSocketHandler 中处理

        // 立即尝试获取许可
        if (tryAcquireAndRun(queue, conversationId, permitRef, cancelled, () -> {
            chatHandler.processMessage(userId, payload, session);
        })) {
            return;
        }

        // 需要排队，发送排队状态
        int position = getQueuePosition(queue, conversationId);
        log.info("[ChatQueue] 对话 {} 排队中，位置={}", conversationId, position);
        sendQueueStatus(session, position);

        // 开始轮询等待
        schedulePolling(queue, conversationId, permitRef, cancelled, session, () -> {
            chatHandler.processMessage(userId, payload, session);
        });
    }

    /**
     * 轮询等待许可。
     */
    private void schedulePolling(RScoredSortedSet<String> queue,
                                  String conversationId,
                                  AtomicReference<String> permitRef,
                                  AtomicBoolean cancelled,
                                  WebSocketSession session,
                                  Runnable onAcquire) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(props.getMaxWaitSeconds());
        int intervalMs = 200; // 轮询间隔

        final ScheduledFuture<?>[] futureRef = new ScheduledFuture<?>[1];

        Runnable poller = () -> {
            // 检查是否取消
            if (cancelled.get()) {
                waitingPollers.remove(conversationId);
                cancelFuture(futureRef[0]);
                return;
            }

            // 检查超时
            if (System.currentTimeMillis() > deadline) {
                waitingPollers.remove(conversationId);
                cancelFuture(futureRef[0]);
                queue.remove(conversationId);
                publishNotify();
                if (!cancelled.get()) {
                    log.warn("[ChatQueue] 对话 {} 等待超时", conversationId);
                    sendTimeoutMessage(session);
                }
                return;
            }

            // 尝试获取许可
            if (tryAcquireAndRun(queue, conversationId, permitRef, cancelled, onAcquire)) {
                waitingPollers.remove(conversationId);
                cancelFuture(futureRef[0]);
            }
        };

        // 注册等待中的轮询器（用于 Pub/Sub 唤醒）
        waitingPollers.put(conversationId, poller);

        // 启动定时轮询
        futureRef[0] = scheduler.scheduleAtFixedRate(poller, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 尝试获取许可并执行任务。
     * @return true 表示成功获取并开始执行
     */
    private boolean tryAcquireAndRun(RScoredSortedSet<String> queue,
                                      String conversationId,
                                      AtomicReference<String> permitRef,
                                      AtomicBoolean cancelled,
                                      Runnable onAcquire) {
        if (cancelled.get()) {
            return false;
        }

        // 检查可用许可数
        int availablePermits = availablePermits();
        if (availablePermits <= 0) {
            return false;
        }

        // Lua 原子出队：检查是否在队头窗口内
        ClaimResult claimResult = claimIfReady(queue, conversationId, availablePermits);
        if (!claimResult.claimed) {
            return false;
        }

        // 出队成功，获取信号量许可
        String permitId = tryAcquirePermit();
        if (permitId == null) {
            // 获取许可失败，重新入队
            long newSeq = nextQueueSeq();
            queue.add(newSeq, conversationId);
            publishNotify();
            log.debug("[ChatQueue] 对话 {} 出队后获取许可失败，重新入队", conversationId);
            return false;
        }

        permitRef.set(permitId);

        if (cancelled.get()) {
            // 已取消，释放许可
            releasePermit(permitId, permitRef);
            return false;
        }

        publishNotify();
        log.info("[ChatQueue] 对话 {} 获得许可，开始执行", conversationId);

        // 执行业务逻辑（同步阻塞，直到 LLM 响应完成）
        try {
            onAcquire.run();
        } catch (Exception e) {
            log.error("[ChatQueue] 执行业务逻辑异常: {}", e.getMessage(), e);
            throw e;
        } finally {
            // 业务完成后释放许可
            releasePermit(permitRef.get(), permitRef);
        }

        return true;
    }

    /**
     * Lua 原子出队。
     */
    private ClaimResult claimIfReady(RScoredSortedSet<String> queue, String conversationId, int availablePermits) {
        RScript script = redisson.getScript(StringCodec.INSTANCE);
        List<Object> result = script.eval(
                RScript.Mode.READ_WRITE,
                claimLuaScript,
                RScript.ReturnType.MULTI,
                List.of(queue.getName()),
                conversationId,
                String.valueOf(availablePermits)
        );

        if (result == null || result.isEmpty()) {
            return ClaimResult.notClaimed();
        }

        long okValue = parseLong(result.get(0));
        if (okValue != 1L) {
            return ClaimResult.notClaimed();
        }

        double score = result.size() > 1 && result.get(1) != null
                ? Double.parseDouble(result.get(1).toString())
                : System.currentTimeMillis();

        return new ClaimResult(true, score);
    }

    private String tryAcquirePermit() {
        try {
            RPermitExpirableSemaphore semaphore = redisson.getPermitExpirableSemaphore(SEMAPHORE_KEY);
            semaphore.trySetPermits(props.getMaxConcurrent());
            // 非阻塞获取，租约 180 秒
            return semaphore.tryAcquire(0, 180, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private int availablePermits() {
        RPermitExpirableSemaphore semaphore = redisson.getPermitExpirableSemaphore(SEMAPHORE_KEY);
        semaphore.trySetPermits(props.getMaxConcurrent());
        return semaphore.availablePermits();
    }

    private void releasePermit(String permitId, AtomicReference<String> permitRef) {
        if (permitRef.compareAndSet(permitId, null)) {
            try {
                redisson.getPermitExpirableSemaphore(SEMAPHORE_KEY).release(permitId);
                publishNotify();
            } catch (Exception e) {
                log.error("[ChatQueue] 释放许可异常: {}", e.getMessage());
            }
        }
    }

    private long nextQueueSeq() {
        RAtomicLong seq = redisson.getAtomicLong(QUEUE_SEQ_KEY);
        return seq.incrementAndGet();
    }

    private int getQueuePosition(RScoredSortedSet<String> queue, String conversationId) {
        Integer rank = queue.rank(conversationId);
        return rank == null ? 1 : rank + 1;
    }

    private void publishNotify() {
        redisson.getTopic(NOTIFY_TOPIC).publish("release");
    }

    private void cancelFuture(ScheduledFuture<?> future) {
        if (future != null && !future.isCancelled()) {
            future.cancel(false);
        }
    }

    private long parseLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }

    private String loadLuaScript() {
        try {
            ClassPathResource resource = new ClassPathResource(CLAIM_LUA_PATH);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("加载 Lua 脚本失败: " + CLAIM_LUA_PATH, e);
        }
    }

    // ---- WebSocket 消息发送 ----

    private void sendQueueStatus(WebSocketSession session, int position) {
        try {
            Map<String, Object> msg = Map.of(
                    "type", "queue_status",
                    "position", position,
                    "message", "当前排队第 " + position + " 位，请稍候..."
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
        } catch (Exception e) {
            log.warn("[ChatQueue] 发送排队状态失败: {}", e.getMessage());
        }
    }

    private void sendTimeoutMessage(WebSocketSession session) {
        try {
            Map<String, Object> msg = Map.of(
                    "type", "queue_timeout",
                    "message", "当前请求量较大，排队超时，请稍后重试"
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
        } catch (Exception e) {
            log.warn("[ChatQueue] 发送超时提示失败: {}", e.getMessage());
        }
    }

    // ---- 内部类 ----

    private record ClaimResult(boolean claimed, double score) {
        static ClaimResult notClaimed() {
            return new ClaimResult(false, 0D);
        }
    }
}
