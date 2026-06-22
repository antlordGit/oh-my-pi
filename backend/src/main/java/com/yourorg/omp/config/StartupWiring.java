package com.yourorg.omp.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.yourorg.omp.audit.AuditService;
import com.yourorg.omp.event.EventBus;
import com.yourorg.omp.maintenance.MaintenanceService;
import com.yourorg.omp.pool.ProcessPool;
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
 * <p>Other listeners (MaintenanceService for streaming-session tracking) are attached via a
 * composite frameHook so each new subscriber only requires editing this file.
 */
@Component
public class StartupWiring {

    private static final Logger log = LoggerFactory.getLogger(StartupWiring.class);

    private final ProcessPool pool;
    private final EventBus eventBus;
    private final AuditService audit;
    private final MaintenanceService maintenance;

    public StartupWiring(ProcessPool pool, EventBus eventBus, AuditService audit,
                         MaintenanceService maintenance) {
        this.pool = pool;
        this.eventBus = eventBus;
        this.audit = audit;
        this.maintenance = maintenance;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void wire() {
        // 组合多个 frame 监听器：sessionId + JSON frame 同时分发给 EventBus 和 MaintenanceService。
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
