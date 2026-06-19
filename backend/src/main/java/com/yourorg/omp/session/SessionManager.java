package com.yourorg.omp.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourorg.omp.audit.AuditService;
import com.yourorg.omp.entity.SessionMeta;
import com.yourorg.omp.pool.ProcessPool;
import com.yourorg.omp.repo.SessionMetaRepository;
import com.yourorg.omp.rpc.OmpRpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Front door for session lifecycle. Owns the SessionMeta rows in MySQL and brokers access
 * to the running OmpRpcClient via the {@link ProcessPool}.
 */
@Service
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SessionMetaRepository repo;
    private final ProcessPool pool;
    private final AuditService audit;

    public SessionManager(SessionMetaRepository repo, ProcessPool pool, AuditService audit) {
        this.repo = repo;
        this.pool = pool;
        this.audit = audit;
    }

    /** Create a new session record. Does NOT spawn a process — that happens on first prompt or state fetch. */
    @Transactional
    public SessionMeta create(Long userId, String repoId, String title) {
        SessionMeta m = new SessionMeta();
        m.setSessionId(UUID.randomUUID().toString());
        m.setUserId(userId);
        m.setRepoId(repoId);
        m.setTitle(title);
        m.setStatus("active");
        m.setLastActiveAt(Instant.now());
        SessionMeta saved = repo.save(m);
        audit.attach(saved.getSessionId());
        return saved;
    }

    public Optional<SessionMeta> findOwned(String sessionId, Long userId) {
        return repo.findBySessionId(sessionId).filter(m -> m.getUserId().equals(userId));
    }

    public Optional<SessionMeta> find(String sessionId) {
        return repo.findBySessionId(sessionId);
    }

    public List<SessionMeta> listByUser(Long userId) {
        return repo.findByUserIdOrderByLastActiveAtDesc(userId);
    }

    public List<SessionMeta> listByUserAndRepo(Long userId, String repoId) {
        return repo.findByUserIdAndRepoIdOrderByLastActiveAtDesc(userId, repoId);
    }

    /** Touch last_active_at; called after every prompt/abort/etc. */
    @Transactional
    public void touch(String sessionId) {
        repo.findBySessionId(sessionId).ifPresent(m -> {
            m.setLastActiveAt(Instant.now());
            repo.save(m);
        });
    }

    @Transactional
    public void updateTitle(String sessionId, String title) {
        repo.findBySessionId(sessionId).ifPresent(m -> {
            m.setTitle(title);
            repo.save(m);
        });
    }

    @Transactional
    public void recordSessionFile(String sessionId, String ompSessionFile) {
        repo.findBySessionId(sessionId).ifPresent(m -> {
            m.setOmpSessionFile(ompSessionFile);
            repo.save(m);
        });
    }

    @Transactional
    public void recordForkParent(String sessionId, String parentSessionFile) {
        repo.findBySessionId(sessionId).ifPresent(m -> {
            m.setParentSessionFile(parentSessionFile);
            repo.save(m);
        });
    }

    @Transactional
    public void archive(String sessionId) {
        repo.findBySessionId(sessionId).ifPresent(m -> {
            m.setStatus("archived");
            repo.save(m);
        });
        pool.evict(sessionId);
    }

    /** Acquire (spawn or reuse) the running OmpRpcClient for this session. */
    public OmpRpcClient acquireClient(SessionMeta meta) {
        log.info("[acquire] session={} user={} repo={} resumePath={}",
                meta.getSessionId(), meta.getUserId(), meta.getRepoId(),
                meta.getOmpSessionFile() == null ? "<none>" : meta.getOmpSessionFile());
        OmpRpcClient client = pool.acquire(meta.getUserId(), meta.getRepoId(), meta.getSessionId(), meta.getOmpSessionFile());
        log.info("[acquire] session={} got client, alive={}", meta.getSessionId(), client.isAlive());
        return client;
    }

    /**
     * Send an RPC command, touch last_active_at on success, and unwrap the response payload.
     * Throws on command failure (response.success==false) or process death.
     */
    public CompletableFuture<JsonNode> sendCommand(SessionMeta meta, com.fasterxml.jackson.databind.node.ObjectNode cmd) {
        log.info("[send] session={} type={}", meta.getSessionId(), cmd.path("type").asText());
        OmpRpcClient client = acquireClient(meta);
        return client.send(cmd).thenApply(resp -> {
            log.info("[send-response] session={} type={} success={}",
                    meta.getSessionId(), cmd.path("type").asText(), resp.path("success").asBoolean());
            return unwrapResponse(resp);
        });
    }

    private JsonNode unwrapResponse(JsonNode response) {
        if (!response.path("success").asBoolean(true)) {
            String err = response.path("error").asText("unknown error");
            throw new RuntimeException("omp rpc error: " + err);
        }
        if (response.has("data")) {
            return response.get("data");
        }
        // No data: return a synthetic "ok" object so callers can still chain.
        return MAPPER.createObjectNode().put("ok", true);
    }

    /** Count active sessions for a user; used to enforce per-user limits. */
    public long countActiveByUser(Long userId) {
        return repo.countByUserIdAndStatus(userId, "active");
    }
}