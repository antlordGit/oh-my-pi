package com.yourorg.omp.config;

import com.yourorg.omp.audit.AuditService;
import com.yourorg.omp.event.EventBus;
import com.yourorg.omp.pool.ProcessPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Wires the EventBus into the ProcessPool: every non-response frame from any omp process
 * is also published to the per-session EventBus, which the WS gateway and AuditService subscribe to.
 */
@Component
public class StartupWiring {

    private static final Logger log = LoggerFactory.getLogger(StartupWiring.class);

    private final ProcessPool pool;
    private final EventBus eventBus;
    private final AuditService audit;

    public StartupWiring(ProcessPool pool, EventBus eventBus, AuditService audit) {
        this.pool = pool;
        this.eventBus = eventBus;
        this.audit = audit;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void wire() {
        pool.setFrameHook((sessionId, frame) -> eventBus.publish(sessionId, frame));
        // Audit subscribes to every session's EventBus stream.
        // Pre-subscribe to known sessions; new sessions are subscribed on-demand via attach().
        audit.attachAll();
        log.info("EventBus ↔ ProcessPool ↔ AuditService wired");
    }
}