package com.yourorg.omp.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.yourorg.omp.entity.PromptAudit;
import com.yourorg.omp.entity.ResponseAudit;
import com.yourorg.omp.entity.ToolAudit;
import com.yourorg.omp.event.EventBus;
import com.yourorg.omp.repo.PromptAuditRepository;
import com.yourorg.omp.repo.ResponseAuditRepository;
import com.yourorg.omp.repo.SessionMetaRepository;
import com.yourorg.omp.repo.ToolAuditRepository;
import com.yourorg.omp.repo.UserRepository;
import com.yourorg.omp.rpc.RpcFrameType;
import com.yourorg.omp.workspace.WorkspaceService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Subscribes to every active OmpRpcClient's event stream and persists audit rows to MySQL.
 *
 * <p>Tracks in-flight assistant turns (collecting streamed text/thinking) and tool calls
 * (matching start/update/end) so we can write one normalized row per completed item.
 *
 * <p>Hooks into {@link EventBus} so multiple subscribers (audit + WS gateway) coexist without
 * consuming each other's data.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final EventBus eventBus;
    private final SessionMetaRepository sessionRepo;
    private final PromptAuditRepository promptRepo;
    private final ToolAuditRepository toolRepo;
    private final ResponseAuditRepository responseRepo;
    private final WorkspaceService workspace;
    private final UserRepository userRepo;

    private final ExecutorService dispatcher = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "audit-dispatcher");
        t.setDaemon(true);
        return t;
    });

    // Per-session in-flight trackers
    private final Map<String, TurnState> turnStates = new ConcurrentHashMap<>();
    private final Map<String, Map<String, ToolState>> toolStates = new ConcurrentHashMap<>();

    public AuditService(EventBus eventBus,
                        SessionMetaRepository sessionRepo,
                        PromptAuditRepository promptRepo,
                        ToolAuditRepository toolRepo,
                        ResponseAuditRepository responseRepo,
                        WorkspaceService workspace,
                        UserRepository userRepo) {
        this.eventBus = eventBus;
        this.sessionRepo = sessionRepo;
        this.promptRepo = promptRepo;
        this.toolRepo = toolRepo;
        this.responseRepo = responseRepo;
        this.workspace = workspace;
        this.userRepo = userRepo;
    }

    /**
     * For every currently-active session, attach to its client's event stream. New sessions
     * are attached lazily in {@link #attach(String)}.
     */
    @PostConstruct
    public void init() {
        // We don't iterate pool here because SessionManager owns meta. Sessions are attached on-demand.
    }

    /**
     * Subscribe to every session's EventBus stream at startup. Sessions created later are
     * attached via {@link #attach(String)}.
     */
    public void attachAll() {
        // No-op: we subscribe per-session on demand. This hook exists for symmetry with StartupWiring.
    }

    /**
     * Subscribe to a single session's EventBus stream. Idempotent — multiple calls just add more subscribers.
     */
    public void attach(String sessionId) {
        eventBus.subscribe(sessionId).subscribe(
                frame -> handleEvent(sessionId, frame),
                err -> log.warn("audit subscription error session={}: {}", sessionId, err.getMessage())
        );
    }

    /**
     * Attach a session's event stream to the audit pipeline. Idempotent.
     * Call when the client is spawned (or right after acquire).
     */
    private void handleEvent(String sessionId, JsonNode frame) {
        String type = frame.path("type").asText("");
        try {
            switch (type) {
                case RpcFrameType.MESSAGE_START -> onMessageStart(sessionId, frame);
                case RpcFrameType.MESSAGE_UPDATE -> onMessageUpdate(sessionId, frame);
                case RpcFrameType.MESSAGE_END -> onMessageEnd(sessionId, frame);
                case RpcFrameType.TOOL_EXECUTION_START -> onToolStart(sessionId, frame);
                case RpcFrameType.TOOL_EXECUTION_UPDATE -> onToolUpdate(sessionId, frame);
                case RpcFrameType.TOOL_EXECUTION_END -> onToolEnd(sessionId, frame);
                case RpcFrameType.SESSION_INFO_UPDATE -> onSessionInfoUpdate(sessionId, frame);
                default -> { /* ignore other events for audit */ }
            }
        } catch (Exception e) {
            log.warn("audit handler error session={} type={}: {}", sessionId, type, e.getMessage());
        }
    }

    // -------- message lifecycle --------

    private void onMessageStart(String sessionId, JsonNode frame) {
        TurnState s = new TurnState();
        s.messageId = frame.path("message").path("id").asText(null);
        turnStates.put(sessionId, s);
    }

    private void onMessageUpdate(String sessionId, JsonNode frame) {
        TurnState s = turnStates.computeIfAbsent(sessionId, k -> new TurnState());
        JsonNode evt = frame.path("assistantMessageEvent");
        String kind = evt.path("type").asText("");
        switch (kind) {
            case "text_delta" -> s.text.append(evt.path("delta").asText(""));
            case "thinking_delta" -> s.thinking.append(evt.path("delta").asText(""));
            case "toolcall" -> s.toolCallIds.add(evt.path("id").asText());
            default -> { /* stop, etc. */ }
        }
    }

    @Transactional
    protected void onMessageEnd(String sessionId, JsonNode frame) {
        TurnState s = turnStates.remove(sessionId);
        if (s == null) return;
        JsonNode msg = frame.path("message");
        var meta = sessionRepo.findBySessionId(sessionId).orElse(null);
        if (meta == null) return;
        ResponseAudit r = new ResponseAudit();
        r.setSessionId(sessionId);
        r.setUserId(meta.getUserId());
        r.setTenantId(meta.getTenantId());
        r.setMessageId(s.messageId != null ? s.messageId : msg.path("id").asText(null));
        r.setFullText(s.text.toString());
        r.setThinking(s.thinking.toString());
        r.setError(!"stop".equals(msg.path("stopReason").asText("stop"))
                && !"toolUse".equals(msg.path("stopReason").asText("toolUse")));
        r.setStopReason(msg.path("stopReason").asText(null));
        r.setFinishedAt(Instant.now());
        responseRepo.save(r);

        // 累加用户已消耗 Token（usage.totalTokens 由 wire 协议在 message 帧中给出）
        long totalTokens = msg.path("usage").path("totalTokens").asLong(0);
        if (totalTokens > 0 && meta.getUserId() != null) {
            userRepo.addTokenUsed(meta.getUserId(), totalTokens);
        }
    }

    // -------- tool lifecycle --------

    private void onToolStart(String sessionId, JsonNode frame) {
        String callId = frame.path("toolCallId").asText();
        ToolState s = new ToolState();
        s.toolName = frame.path("toolName").asText();
        s.arguments = frame.path("arguments");
        s.startedAt = Instant.now();
        toolStates.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>()).put(callId, s);
    }

    private void onToolUpdate(String sessionId, JsonNode frame) {
        // Some tools emit partial updates; we currently just ignore for audit.
    }

    @Async
    @Transactional
    protected void onToolEnd(String sessionId, JsonNode frame) {
        String callId = frame.path("toolCallId").asText();
        Map<String, ToolState> m = toolStates.get(sessionId);
        ToolState s = m != null ? m.remove(callId) : null;
        var meta = sessionRepo.findBySessionId(sessionId).orElse(null);
        if (meta == null || s == null) return;

        ToolAudit t = new ToolAudit();
        t.setSessionId(sessionId);
        t.setUserId(meta.getUserId());
        t.setTenantId(meta.getTenantId());
        t.setToolCallId(callId);
        t.setToolName(s.toolName);
        // Sanitize null/empty fields so MySQL TEXT accepts
        t.setArguments(s.arguments != null && !s.arguments.isNull() ? s.arguments.toString() : null);
        JsonNode resultNode = frame.path("result");
        t.setResult(resultNode != null && !resultNode.isNull() ? resultNode.toString() : null);
        t.setError(frame.path("isError").asBoolean(false));
        t.setStartedAt(s.startedAt);
        t.setEndedAt(Instant.now());
        try {
            toolRepo.save(t);
            log.debug("audit tool_end session={} tool={} ok", sessionId, s.toolName);
        } catch (Exception ex) {
            log.warn("audit tool_end persist failed session={} tool={}: {}", sessionId, s.toolName, ex.getMessage());
        }

        // Snapshot the workspace after each tool execution so file history is queryable via git.
        try {
            String sha = workspace.snapshot(meta.getUserId(), meta.getRepoId(),
                    s.toolName + ": " + callId.substring(0, Math.min(8, callId.length()))).orElse(null);
            if (sha != null) log.debug("snapshot session={} tool={} → {}", sessionId, s.toolName, sha);
        } catch (Exception e) {
            log.warn("snapshot failed session={}: {}", sessionId, e.getMessage());
        }
    }

    private void onSessionInfoUpdate(String sessionId, JsonNode frame) {
        String title = frame.path("title").asText(null);
        String sessionFile = frame.path("sessionFile").asText(null);
        sessionRepo.findBySessionId(sessionId).ifPresent(m -> {
            if (title != null) m.setTitle(title);
            if (sessionFile != null && !sessionFile.isBlank()) m.setOmpSessionFile(sessionFile);
            m.setLastActiveAt(Instant.now());
            sessionRepo.save(m);
        });
    }

    // -------- public write APIs (called from REST when user sends a prompt) --------

    @Transactional
    public void recordPrompt(String sessionId, Long userId, Long tenantId, String text, JsonNode imagesJson) {
        PromptAudit p = new PromptAudit();
        p.setSessionId(sessionId);
        p.setUserId(userId);
        p.setTenantId(tenantId);
        p.setPromptText(text);
        p.setPromptImagesJson(imagesJson != null && !imagesJson.isNull() ? imagesJson.toString() : null);
        promptRepo.save(p);
    }

    @PreDestroy
    public void shutdown() {
        dispatcher.shutdownNow();
    }

    private static final class TurnState {
        String messageId;
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        java.util.Set<String> toolCallIds = new java.util.HashSet<>();
    }

    private static final class ToolState {
        String toolName;
        JsonNode arguments;
        Instant startedAt;
    }
}