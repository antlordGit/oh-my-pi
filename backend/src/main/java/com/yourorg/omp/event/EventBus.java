package com.yourorg.omp.event;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session event fan-out. Each sessionId has its own multicast sink; subscribers (WS gateway,
 * audit service) attach/detach as connections come and go.
 */
@Component
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final Map<String, Sinks.Many<JsonNode>> sessionSinks = new ConcurrentHashMap<>();

    /** Get (creating if needed) the per-session event sink. */
    public Sinks.Many<JsonNode> sink(String sessionId) {
        return sessionSinks.computeIfAbsent(sessionId,
                id -> Sinks.many().multicast().directBestEffort());
    }

    /** Get a Flux view of a session's events. */
    public Flux<JsonNode> subscribe(String sessionId) {
        return sink(sessionId).asFlux();
    }

    /** Publish an event frame to a session. */
    public void publish(String sessionId, JsonNode frame) {
        Sinks.Many<JsonNode> s = sessionSinks.get(sessionId);
        if (s != null) {
            s.tryEmitNext(frame);
        }
    }

    /** Tear down a session's sink (called when the session is archived or its process dies permanently). */
    public void dispose(String sessionId) {
        Sinks.Many<JsonNode> s = sessionSinks.remove(sessionId);
        if (s != null) {
            s.tryEmitComplete();
            log.debug("Disposed event sink for session={}", sessionId);
        }
    }
}