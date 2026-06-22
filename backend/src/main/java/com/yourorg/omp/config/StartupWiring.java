package com.yourorg.omp.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.yourorg.omp.audit.AuditService;
import com.yourorg.omp.event.EventBus;
import com.yourorg.omp.maintenance.MaintenanceService;
import com.yourorg.omp.pool.ProcessPool;
import com.yourorg.omp.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Wires the EventBus into the ProcessPool: every non-response frame from any omp process
 * is also published to the per-session EventBus, which the WS gateway and AuditService subscribe to.
 *
 * <p>启动时执行：
 * <ul>
 *   <li>统一归档所有残留 active 会话（进程重启后子进程已死亡，DB 状态需对齐）</li>
 *   <li>挂载 frameHook 多播：EventBus 发布 + MaintenanceService 推流追踪</li>
 *   <li>AuditService 订阅全部会话流</li>
 * </ul>
 */
@Component
public class StartupWiring {

    private static final Logger log = LoggerFactory.getLogger(StartupWiring.class);

    private final ProcessPool pool;
    private final EventBus eventBus;
    private final AuditService audit;
    private final MaintenanceService maintenance;
    private final SessionManager sessions;

    public StartupWiring(ProcessPool pool, EventBus eventBus, AuditService audit,
                         MaintenanceService maintenance, SessionManager sessions) {
        this.pool = pool;
        this.eventBus = eventBus;
        this.audit = audit;
        this.maintenance = maintenance;
        this.sessions = sessions;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void wire() {
        // 统一归档所有残留 active 会话：容器/进程重启后所有 omp 子进程都已死亡，
        // 数据库的 active 状态与现实不一致，统一标记为 archived。用户下次继续对话
        // 时点"恢复"按钮，会以 --resume <sessionFile> 拉起新进程并恢复历史。
        int archived = sessions.archiveAllOnStartup();
        if (archived > 0) {
            log.info("Startup: archived {} stale active sessions (process group restarted)", archived);
        }

        // frameHook 多播：按顺序通知所有订阅者
        List<BiConsumer<String, JsonNode>> hooks = new ArrayList<>();
        hooks.add((sid, frame) -> eventBus.publish(sid, frame));
        hooks.add((sid, frame) -> maintenance.onFrame(sid, frame));

        pool.setFrameHook((sessionId, frame) -> {
            for (var hook : hooks) {
                try { hook.accept(sessionId, frame); }
                catch (Exception e) { log.warn("frame hook error session={}: {}", sessionId, e.getMessage(), e); }
            }
        });

        // Audit subscribes to every session's EventBus stream.
        // Pre-subscribe to known sessions; new sessions are subscribed on-demand via attach().
        audit.attachAll();
        log.info("EventBus + MaintenanceService wired into ProcessPool frameHook");
    }
}
