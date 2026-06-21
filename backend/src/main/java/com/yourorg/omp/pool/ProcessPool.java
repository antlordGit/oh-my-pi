package com.yourorg.omp.pool;

import com.yourorg.omp.config.OmpProperties;
import com.yourorg.omp.rpc.OmpRpcClient;
import com.yourorg.omp.rpc.OmpRpcClientFactory;
import com.yourorg.omp.rpc.OmpStartupException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Bounded pool of {@link OmpRpcClient} instances. Each sessionId maps to at most one client.
 *
 * <p>Concurrency cap is enforced by a {@link Semaphore}: acquiring a slot blocks until one is free.
 * The total active count is at most {@code pool.max-concurrent}, regardless of distinct sessionIds —
 * callers that exceed the cap will block at {@link #acquire}.
 *
 * <p>Idle eviction runs every minute and kills clients not used in {@code idle-ttl-minutes}.
 */
@Component
public class ProcessPool {

    private static final Logger log = LoggerFactory.getLogger(ProcessPool.class);

    private final Semaphore slots;
    private final OmpProperties props;
    private final OmpRpcClientFactory factory;

    private final Map<String, Entry> active = new ConcurrentHashMap<>();
    /** Per-session locks to prevent concurrent spawn races (multiple acquire() racing on the same session). */
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public ProcessPool(OmpProperties props, OmpRpcClientFactory factory) {
        this.props = props;
        this.factory = factory;
        this.slots = new Semaphore(props.pool().maxConcurrent());
    }

    /**
     * Hook invoked once per non-response frame after a fresh client spawn.
     * Set by ApplicationReadyListener to wire EventBus fan-out.
     */
    private volatile java.util.function.BiConsumer<String, com.fasterxml.jackson.databind.JsonNode> frameHook;

    public void setFrameHook(java.util.function.BiConsumer<String, com.fasterxml.jackson.databind.JsonNode> hook) {
        this.frameHook = hook;
    }

    /**
     * Get-or-create the client for a session. Blocks if pool is at capacity.
     *
     * @param userId, repoId used to resolve workspace + agentDir
     * @param sessionId the omp session id (used as the pool key)
     * @param resumePath optional omp session JSONL to resume; null for new sessions
     */
    public OmpRpcClient acquire(Long userId, String repoId, String sessionId, String resumePath) {
        // Hold a per-session lock so concurrent acquire() calls for the same session don't race
        // and spawn multiple omp processes (we used to leak slots this way).
        Object lock = sessionLocks.computeIfAbsent(sessionId, k -> new Object());
        synchronized (lock) {
            Entry existing = active.get(sessionId);
            boolean alive = existing != null && existing.client.isAlive();
            log.info("[pool.acquire] session={} existing={} alive={} usedSlots={}/{}",
                    sessionId, existing != null, alive, usedSlots(), props.pool().maxConcurrent());
            if (existing != null && alive) {
                existing.touch();
                return existing.client;
            }
            if (existing != null) {
                // Stale: dead client. Release the slot and remove from map.
                log.warn("[pool.acquire] session={} stale entry, evicting", sessionId);
                slots.release();
                active.remove(sessionId, existing);
                try { existing.client.close(); } catch (Exception ignore) {}
            }
            if (!slots.tryAcquire()) {
                log.warn("[pool.acquire] session={} pool full ({}/{})—blocking",
                        sessionId, usedSlots(), props.pool().maxConcurrent());
                slots.acquireUninterruptibly();
            }
            OmpRpcClient client;
            try {
                client = factory.spawn(sessionId, userId, repoId, resumePath);
                log.info("[pool.acquire] session={} spawned fresh omp pid-alive={}", sessionId, client.isAlive());
            } catch (IOException | OmpStartupException e) {
                slots.release();
                log.error("[pool.acquire] session={} spawn FAILED: {}", sessionId, e.getMessage());
                throw new RuntimeException("为会话启动 omp 进程失败: " + sessionId, e);
            }
            Entry entry = new Entry(client, Instant.now());
            active.put(sessionId, entry);
            if (frameHook != null) {
                client.setFanout(frame -> frameHook.accept(sessionId, frame));
            }
            log.info("Spawned omp session={} slots in use={}/{}", sessionId, usedSlots(), props.pool().maxConcurrent());
            return client;
        }
    }

    /**
     * Force-evict a session's client (e.g. admin kill, session archive). Closes the process and releases the slot.
     */
    public void evict(String sessionId) {
        Entry entry = active.remove(sessionId);
        if (entry != null) {
            entry.client.close();
            slots.release();
            log.info("Evicted omp session={} slots in use={}/{}", sessionId, usedSlots(), props.pool().maxConcurrent());
        }
    }

    public boolean isActive(String sessionId) {
        Entry e = active.get(sessionId);
        return e != null && e.client.isAlive();
    }

    public int usedSlots() {
        return props.pool().maxConcurrent() - slots.availablePermits();
    }

    public int activeCount() {
        return active.size();
    }

    /** Runs every 60s, kills clients whose lastUsedAt is older than idleTtl. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void evictIdle() {
        Duration ttl = Duration.ofMinutes(props.pool().idleTtlMinutes());
        Instant cutoff = Instant.now().minus(ttl);
        for (Map.Entry<String, Entry> mapEntry : active.entrySet()) {
            if (mapEntry.getValue().lastUsedAt.isBefore(cutoff)) {
                log.info("Idle-evicting omp session={} (idle > {}m)", mapEntry.getKey(), props.pool().idleTtlMinutes());
                evict(mapEntry.getKey());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        for (String sid : active.keySet().toArray(new String[0])) {
            evict(sid);
        }
    }

    private static final class Entry {
        final OmpRpcClient client;
        volatile Instant lastUsedAt;

        Entry(OmpRpcClient client, Instant lastUsedAt) {
            this.client = client;
            this.lastUsedAt = lastUsedAt;
        }

        void touch() {
            lastUsedAt = Instant.now();
        }
    }
}