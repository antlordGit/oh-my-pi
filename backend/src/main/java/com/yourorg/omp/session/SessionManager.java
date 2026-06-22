package com.yourorg.omp.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourorg.omp.audit.AuditService;
import com.yourorg.omp.entity.SessionMeta;
import com.yourorg.omp.pool.ProcessPool;
import com.yourorg.omp.repo.PromptAuditRepository;
import com.yourorg.omp.repo.ResponseAuditRepository;
import com.yourorg.omp.repo.SessionMetaRepository;
import com.yourorg.omp.repo.ToolAuditRepository;
import com.yourorg.omp.rpc.OmpRpcClient;
import com.yourorg.omp.rpc.RpcCommands;
import com.yourorg.omp.security.DataScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
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
    private final PromptAuditRepository promptAuditRepo;
    private final ToolAuditRepository toolAuditRepo;
    private final ResponseAuditRepository responseAuditRepo;

    public SessionManager(SessionMetaRepository repo,
                          ProcessPool pool,
                          AuditService audit,
                          PromptAuditRepository promptAuditRepo,
                          ToolAuditRepository toolAuditRepo,
                          ResponseAuditRepository responseAuditRepo) {
        this.repo = repo;
        this.pool = pool;
        this.audit = audit;
        this.promptAuditRepo = promptAuditRepo;
        this.toolAuditRepo = toolAuditRepo;
        this.responseAuditRepo = responseAuditRepo;
    }

    /** Create a new session record. Does NOT spawn a process — that happens on first prompt or state fetch. */
    @Transactional
    public SessionMeta create(Long userId, Long tenantId, String repoId, String title) {
        SessionMeta m = new SessionMeta();
        m.setSessionId(UUID.randomUUID().toString());
        m.setUserId(userId);
        m.setTenantId(tenantId);
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

    /** 按数据范围查找单个会话：普通用户限本人，管理员限本租户，超管不限。 */
    public Optional<SessionMeta> findScoped(String sessionId, DataScope scope) {
        return repo.findScopedBySessionId(sessionId, scope.userId(), scope.tenantId());
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

    /** 按数据范围列出会话。 */
    public List<SessionMeta> listScoped(DataScope scope) {
        return repo.findScoped(scope.userId(), scope.tenantId());
    }

    /** 按数据范围列出指定仓库的会话。 */
    public List<SessionMeta> listScopedByRepo(DataScope scope, String repoId) {
        return repo.findScopedByRepo(scope.userId(), scope.tenantId(), repoId);
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

    @Transactional
    public void unarchive(String sessionId) {
        repo.findBySessionId(sessionId).ifPresent(m -> {
            m.setStatus("active");
            m.setLastActiveAt(Instant.now());
            repo.save(m);
        });
    }

    /**
     * 彻底删除一个会话：
     * 1. evict 进程（如果还活着）
     * 2. 删除 DB 主行
     * 3. 删除三张审计表中所有该 sessionId 的行
     * 4. 异步删除磁盘工作目录（/workspaces/{sessionId}/）与 ompSessionFile 指向的会话文件
     *
     * 仅适用于已归档的会话；调用方应先判断 status == "archived"。
     *
     * @return true 表示主行存在并删除；false 表示会话不存在
     */
    @Transactional
    public boolean delete(String sessionId) {
        Optional<SessionMeta> opt = repo.findBySessionId(sessionId);
        if (opt.isEmpty()) return false;
        SessionMeta m = opt.get();
        if (!"archived".equals(m.getStatus())) {
            throw new IllegalStateException("仅归档会话可删除，当前状态：" + m.getStatus());
        }

        // 1. 进程 evict（如果还活着则优雅关闭）
        try { pool.evict(sessionId); } catch (Exception ignored) { /* 可能未拉起过 */ }

        // 2. 审计清理（必须在主行删除前，因为 sessionId 是软引用；删除后仍可按 sessionId 匹配）
        long p = promptAuditRepo.deleteBySessionId(sessionId);
        long t = toolAuditRepo.deleteBySessionId(sessionId);
        long r = responseAuditRepo.deleteBySessionId(sessionId);
        log.info("[delete] session={} cleaned audits prompt={} tool={} response={}", sessionId, p, t, r);

        // 3. DB 主行
        repo.delete(m);
        repo.flush();

        // 4. 磁盘清理（事务外异步，失败不影响主流程）
        // 工作目录是按 {userId}/{repoId} 共享的，多个 session 共用同一目录，
        // 这里只清理 session 自身的 .omp 会话文件，不动工作目录。
        final String ompSessionFile = m.getOmpSessionFile();
        CompletableFuture.runAsync(() -> deleteOnDisk(ompSessionFile));
        return true;
    }

    private void deleteOnDisk(String ompSessionFile) {
        try {
            if (ompSessionFile != null && !ompSessionFile.isBlank()) {
                Path sf = Path.of(ompSessionFile);
                if (Files.exists(sf)) {
                    Files.deleteIfExists(sf);
                    log.info("[delete] removed session file: {}", sf);
                }
            }
        } catch (Exception e) {
            log.warn("[delete] disk cleanup failed: {}", e.getMessage());
        }
    }

    /**
     * 启动钩子：把所有 status=active 的会话统一标记为 archived。
     * <p>由 {@link com.yourorg.omp.config.StartupWiring} 在 {@code ApplicationReadyEvent} 时调用。
     * 此时 ProcessPool 是空的（容器/进程刚启动），不需要 evict。
     * <p>用户下次访问会话时，可通过 {@link #unarchive} 或 {@link #resumeSession} 拉起新进程
     * 并以 {@code --resume <sessionFile>} 恢复历史对话。
     *
     * @return 被归档的会话数
     */
    @Transactional
    public int archiveAllOnStartup() {
        List<SessionMeta> actives = repo.findByStatus("active");
        if (actives.isEmpty()) return 0;
        Instant now = Instant.now();
        for (SessionMeta m : actives) {
            m.setStatus("archived");
            m.setLastActiveAt(now);
        }
        repo.saveAll(actives);
        return actives.size();
    }

    /**
     * Force the pool to evict the session so the next sendCommand/prompt will
     * spawn a fresh omp process with {@code --resume <sessionFile>}, thereby
     * restoring the saved conversation history.
     */
    public void resumeSession(String sessionId) {
        pool.evict(sessionId);
    }

    /** Acquire (spawn or reuse) the running OmpRpcClient for this session. */
    public OmpRpcClient acquireClient(SessionMeta meta) {
        log.info("[acquire] session={} user={} repo={} resumePath={}",
                meta.getSessionId(), meta.getUserId(), meta.getRepoId(),
                meta.getOmpSessionFile() == null ? "<none>" : meta.getOmpSessionFile());
        OmpRpcClient client = pool.acquire(meta.getUserId(), meta.getRepoId(), meta.getSessionId(), meta.getOmpSessionFile());
        log.info("[acquire] session={} got client, alive={}", meta.getSessionId(), client.isAlive());
        // First-time capture of omp's on-disk session file so a later resume (--resume) can
        // restore the conversation. omp never volunteers this path over session_info_update,
        // so we read it once via get_state and persist it. Guarded by ompSessionFile == null
        // to avoid repeat round-trips; sent via client.send() directly (NOT sendCommand) to
        // avoid re-entering acquireClient.
        if (meta.getOmpSessionFile() == null || meta.getOmpSessionFile().isBlank()) {
            captureSessionFile(meta.getSessionId(), client);
        }
        return client;
    }

    /**
     * Ask the running omp process for its session file path and persist it. Best-effort and
     * asynchronous: failures are logged but never block the caller. Once recorded, a subsequent
     * resume spawns omp with {@code --resume <sessionFile>} and restores the saved history.
     */
    private void captureSessionFile(String sessionId, OmpRpcClient client) {
        if (!client.isAlive()) return;
        client.send(RpcCommands.getState()).whenComplete((resp, err) -> {
            if (err != null) {
                log.debug("[capture-session-file] session={} get_state failed: {}", sessionId, err.getMessage());
                return;
            }
            JsonNode data = resp == null ? null : resp.path("data");
            String sessionFile = data == null ? null : data.path("sessionFile").asText(null);
            if (sessionFile == null || sessionFile.isBlank()) {
                log.debug("[capture-session-file] session={} no sessionFile in get_state", sessionId);
                return;
            }
            recordSessionFile(sessionId, sessionFile);
            log.info("[capture-session-file] session={} recorded sessionFile={}", sessionId, sessionFile);
        });
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

    /**
     * 强制 evict 所有存活的 omp 进程。下次发消息时会以 {@code --resume <sessionFile>} 重启，
     * 历史靠 session 文件恢复；进程重启意味着重新读取 models.yml，加载最新的 apiKey/baseUrl。
     *
     * <p>用途：跨 provider 切换激活模型时——OMP 的 ModelRegistry 在启动时一次性加载并固化 apiKey
     * 到 AuthStorage#configOverrides，运行中改 models.yml 不会重读。所以新 provider 的 key 必须
     * 通过进程重启才能生效。
     */
    public void evictAll() {
        List<String> sessionIds = pool.activeSessionIds();
        log.info("[evict-all] evicting {} live omp processes", sessionIds.size());
        for (String sid : sessionIds) {
            pool.evict(sid);
        }
    }

    /**
     * 把激活的模型动态广播给所有正在运行的 omp 进程，复刻 CLI 的 {@code /model} 行为：
     * 进程不重启、对话历史不丢，下一次提问即用新模型。
     *
     * <p>对每个存活会话发送 {@code set_model} RPC。若进程的模型注册表里没有该模型
     * （多见于进程启动后才新建、从未写入 models.yml 的模型，返回 {@code Model not found}），
     * 则兜底 evict 该进程——下次发消息时会以 {@code --resume} 重启并加载新模型，历史靠 session 文件恢复。
     *
     * <p>全程异步、best-effort：单个会话失败只记日志，不影响其他会话，也不阻塞激活流程。
     */
    public void broadcastSetModel(String provider, String modelId) {
        if (provider == null || provider.isBlank() || modelId == null || modelId.isBlank()) {
            log.warn("[broadcast-set-model] skipped: provider/modelId blank (provider={}, modelId={})", provider, modelId);
            return;
        }
        List<String> sessionIds = pool.activeSessionIds();
        log.info("[broadcast-set-model] provider={} modelId={} targets={}", provider, modelId, sessionIds.size());
        for (String sessionId : sessionIds) {
            SessionMeta meta = repo.findBySessionId(sessionId).orElse(null);
            if (meta == null) continue;
            sendCommand(meta, RpcCommands.setModel(provider, modelId))
                    .whenComplete((resp, err) -> {
                        if (err != null) {
                            // set_model 失败（多为模型未在注册表）：evict 让下次重启加载新模型
                            log.warn("[broadcast-set-model] session={} set_model failed, evicting for restart: {}",
                                    sessionId, err.getMessage());
                            pool.evict(sessionId);
                        } else {
                            log.info("[broadcast-set-model] session={} switched to {}/{}", sessionId, provider, modelId);
                        }
                    });
        }
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