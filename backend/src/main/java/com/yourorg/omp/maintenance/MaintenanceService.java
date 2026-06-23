package com.yourorg.omp.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.yourorg.omp.repo.RepoRepository;
import com.yourorg.omp.rpc.RpcFrameType;
import com.yourorg.omp.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统维护服务。
 * <p>
 * 核心职责：
 * <ol>
 *   <li>读写 Redis 中的维护开关状态（7天 TTL）</li>
 *   <li>内存追踪当前正在推流的会话（turn_start / turn_end 事件）</li>
 *   <li>对外提供「是否维护中」「当前推流会话列表」查询</li>
 * </ol>
 *
 * <p>推流事件挂载方式：由 {@link com.yourorg.omp.config.StartupWiring} 注入到
 * {@link com.yourorg.omp.pool.ProcessPool} 的 frameHook，与 EventBus 共享 fan-out。
 * 不要直接 subscribe EventBus("*")，那是一个普通 sessionId 而非通配符。
 */
@Service
public class MaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);

    /** Redis key：维护状态 */
    private static final String MAINTENANCE_KEY = "omp:maintenance:status";
    /** 维护模式持续时间（7天） */
    private static final Duration MAINTENANCE_TTL = Duration.ofDays(7);

    private final RedisTemplate<String, Object> redis;
    private final SessionManager sessionManager;
    private final RepoRepository repoRepository;

    /** 正在推流的会话：sessionId -> 推流开始时间 */
    private final ConcurrentHashMap<String, Instant> streamingSessions = new ConcurrentHashMap<>();

    public MaintenanceService(RedisTemplate<String, Object> redis,
                              SessionManager sessionManager,
                              RepoRepository repoRepository) {
        this.redis = redis;
        this.sessionManager = sessionManager;
        this.repoRepository = repoRepository;
    }

    // ------------------------------------------------------------------------
    // 维护开关 API
    // ------------------------------------------------------------------------

    /**
     * 开启系统维护模式。
     * @param operator 操作者（admin 用户名）
     */
    public void enable(String operator) {
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", true);
        status.put("createdAt", Instant.now().toString());
        status.put("expiresAt", Instant.now().plus(MAINTENANCE_TTL).toString());
        status.put("enabledBy", operator);
        redis.opsForValue().set(MAINTENANCE_KEY, status, MAINTENANCE_TTL);
        log.warn("Maintenance mode ENABLED by {} — new sessions / prompts will be rejected", operator);
    }

    /**
     * 解除系统维护模式。
     */
    public void disable() {
        redis.delete(MAINTENANCE_KEY);
        log.warn("Maintenance mode DISABLED — services restored");
    }

    /**
     * 查询当前是否处于维护模式。
     */
    public boolean isEnabled() {
        Map<String, Object> status = getStatus();
        return status != null && Boolean.TRUE.equals(status.get("enabled"));
    }

    /**
     * 获取维护状态详情。
     * @return 若未开启维护返回 null，否则返回完整状态 map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getStatus() {
        Object v = redis.opsForValue().get(MAINTENANCE_KEY);
        if (v instanceof Map) return (Map<String, Object>) v;
        return null;
    }

    // ------------------------------------------------------------------------
    // 推流会话追踪
    // ------------------------------------------------------------------------

    /**
     * 获取当前正在推流的会话列表（带元数据）。
     * 用于管理员判断「是否可以执行更新」。
     */
    public List<Map<String, Object>> getStreamingSessions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Instant> e : streamingSessions.entrySet()) {
            String sessionId = e.getKey();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sessionId", sessionId);
            item.put("streamingSince", e.getValue().toString());
            item.put("streamingSeconds", Duration.between(e.getValue(), Instant.now()).getSeconds());

            // 额外带上会话标题 / 用户 / 仓库名称（方便前端展示）
            sessionManager.find(sessionId).ifPresent(m -> {
                item.put("title", m.getTitle() == null ? "" : m.getTitle());
                item.put("userId", m.getUserId());
                item.put("repoId", m.getRepoId());
                try {
                    repoRepository.findByUserIdAndRepoId(m.getUserId(), m.getRepoId())
                        .ifPresent(repo -> item.put("repoName", repo.getDisplayName()));
                } catch (Exception ignored) {
                    // 仓库可能已被删除，忽略异常
                }
            });
            result.add(item);
        }
        result.sort((a, b) -> Long.compare(
            ((Number) b.get("streamingSeconds")).longValue(),
            ((Number) a.get("streamingSeconds")).longValue()
        ));
        return result;
    }

    /**
     * 处理 ProcessPool 推来的帧，维护推流集合。
     * <p>sessionId 由 ProcessPool 通过 frameHook 的第一个参数传入（不是从 frame 字段读取）。
     */
    public void onFrame(String sessionId, JsonNode frame) {
        if (sessionId == null || frame == null || !frame.has("type")) return;
        String type = frame.get("type").asText();

        if (RpcFrameType.TURN_START.equals(type)) {
            streamingSessions.put(sessionId, Instant.now());
            log.debug("[maintenance] turn_start: added session {} to streaming set (now {} active)",
                    sessionId, streamingSessions.size());
        } else if (RpcFrameType.TURN_END.equals(type)) {
            streamingSessions.remove(sessionId);
            log.debug("[maintenance] turn_end: removed session {} from streaming set (now {} active)",
                    sessionId, streamingSessions.size());
        }
    }

    /**
     * 强制移除某个会话（会话被终止 / 进程异常退出时调用）。
     */
    public void evictSession(String sessionId) {
        streamingSessions.remove(sessionId);
    }
}
