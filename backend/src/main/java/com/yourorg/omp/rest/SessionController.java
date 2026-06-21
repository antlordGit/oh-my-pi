package com.yourorg.omp.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.yourorg.omp.audit.AuditService;
import com.yourorg.omp.config.OmpProperties;
import com.yourorg.omp.entity.SessionMeta;
import com.yourorg.omp.pool.ProcessPool;
import com.yourorg.omp.rpc.OmpRpcClient;
import com.yourorg.omp.rpc.RpcCommands;
import com.yourorg.omp.security.CurrentUser;
import com.yourorg.omp.entity.User;
import com.yourorg.omp.repo.UserRepository;
import com.yourorg.omp.session.SessionManager;
import com.yourorg.omp.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final SessionManager sessions;
    private final ProcessPool pool;
    private final AuditService audit;
    private final OmpProperties props;
    private final CurrentUser currentUser;
    private final WorkspaceService workspace;
    private final UserRepository users;
    private final com.yourorg.omp.ide.IdeService ideService;

    public SessionController(SessionManager sessions,
                             ProcessPool pool,
                             AuditService audit,
                             OmpProperties props,
                             CurrentUser currentUser,
                             WorkspaceService workspace,
                             UserRepository users,
                             com.yourorg.omp.ide.IdeService ideService) {
        this.sessions = sessions;
        this.pool = pool;
        this.audit = audit;
        this.props = props;
        this.currentUser = currentUser;
        this.workspace = workspace;
        this.users = users;
        this.ideService = ideService;
    }

    public record CreateSessionRequest(String repoId, String title) {}
    public record PromptRequest(String message, String streamingBehavior) {}
    public record SwitchRequest(String sessionPath) {}
    public record BranchRequest(String entryId) {}
    public record NewSessionRequest(String parentSession) {}

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String repoId) {
        var scope = currentUser.scope();
        var list = repoId == null
                ? sessions.listScoped(scope)
                : sessions.listScopedByRepo(scope, repoId);
        return list.stream().map(this::toDto).toList();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateSessionRequest req) {
        var self = currentUser.require();
        Long uid = self.getId();
        long active = sessions.countActiveByUser(uid);
        if (active >= props.perUserSessionLimit()) {
            throw new RuntimeException("当前用户会话数已达上限 (" + props.perUserSessionLimit() + ")");
        }
        checkDiskQuota(self);
        SessionMeta m = sessions.create(uid, self.getTenantId(), req.repoId(), req.title());
        // Eagerly attach audit so the first events are captured.
        audit.attach(m.getSessionId());
        return toDto(m);
    }

    @GetMapping("/{sessionId}")
    public Map<String, Object> get(@PathVariable String sessionId) {
        SessionMeta m = sessions.findScoped(sessionId, currentUser.scope())
                .orElseThrow(() -> new RuntimeException("会话不存在"));
        return toDto(m);
    }

    /**
     * 打开 IDE（code-server / openvscode-server），指向该会话的工作区目录。
     * 基于会话记录解析工作区，不依赖 repos 表。
     */
    @PostMapping("/{sessionId}/ide/open")
    public Map<String, Object> openIde(@PathVariable String sessionId) {
        String url = ideService.buildIdeUrlForSession(sessionId);
        return Map.of("url", url);
    }

    @GetMapping("/{sessionId}/state")
    public JsonNode getState(@PathVariable String sessionId) throws Exception {
        SessionMeta m = require(sessionId);
        return sessions.sendCommand(m, RpcCommands.getState()).get();
    }

    @GetMapping("/{sessionId}/messages")
    public JsonNode messages(@PathVariable String sessionId) throws Exception {
        SessionMeta m = require(sessionId);
        return sessions.sendCommand(m, RpcCommands.getMessages()).get();
    }

    @PostMapping("/{sessionId}/prompt")
    public Map<String, Object> prompt(@PathVariable String sessionId, @RequestBody PromptRequest req) throws Exception {
        log.info("[prompt] hit session={} msgLen={} streamingBehavior={}",
                sessionId, req.message() == null ? 0 : req.message().length(), req.streamingBehavior());
        SessionMeta m = require(sessionId);
        log.info("[prompt] session={} meta ok status={}", sessionId, m.getStatus());
        // 磁盘配额检查：超限时拒绝发送
        checkDiskQuotaByUserId(m.getUserId());
        // Token 额度检查：额度耗尽时拒绝发送
        checkTokenQuotaByUserId(m.getUserId());
        // Archived sessions auto-promote to active when a new prompt is sent.
        if ("archived".equals(m.getStatus())) {
            sessions.unarchive(sessionId);
            m.setStatus("active");
        }
        // Audit the prompt itself (audit-pipeline picks up streamed response events).
        audit.recordPrompt(sessionId, m.getUserId(), m.getTenantId(), req.message(), null);
        // Fire-and-forget: the response comes over WS, not this REST call.
        sessions.sendCommand(m, req.streamingBehavior() != null
                ? RpcCommands.prompt(req.message(), req.streamingBehavior())
                : RpcCommands.prompt(req.message()));
        sessions.touch(sessionId);
        return Map.of("ok", true, "sessionId", sessionId);
    }

    @PostMapping("/{sessionId}/abort")
    public Map<String, Object> abort(@PathVariable String sessionId) throws Exception {
        SessionMeta m = require(sessionId);
        sessions.sendCommand(m, RpcCommands.abort()).get();
        return Map.of("ok", true);
    }

    @PostMapping("/{sessionId}/steer")
    public Map<String, Object> steer(@PathVariable String sessionId, @RequestBody PromptRequest req) throws Exception {
        SessionMeta m = require(sessionId);
        sessions.sendCommand(m, RpcCommands.steer(req.message())).get();
        return Map.of("ok", true);
    }

    @PostMapping("/{sessionId}/follow-up")
    public Map<String, Object> followUp(@PathVariable String sessionId, @RequestBody PromptRequest req) throws Exception {
        SessionMeta m = require(sessionId);
        sessions.sendCommand(m, RpcCommands.followUp(req.message())).get();
        return Map.of("ok", true);
    }

    @PostMapping("/{sessionId}/switch")
    public Map<String, Object> switchSession(@PathVariable String sessionId, @RequestBody SwitchRequest req) throws Exception {
        SessionMeta m = require(sessionId);
        sessions.sendCommand(m, RpcCommands.switchSession(req.sessionPath())).get();
        sessions.recordSessionFile(sessionId, req.sessionPath());
        sessions.touch(sessionId);
        return Map.of("ok", true);
    }

    @PostMapping("/{sessionId}/branch")
    public Map<String, Object> branch(@PathVariable String sessionId, @RequestBody BranchRequest req) throws Exception {
        SessionMeta m = require(sessionId);
        // branch is fire-and-forget: omp emits session_info_update asynchronously
        // with the new sessionFile once the fork is complete. The frontend watches
        // the WS stream and refreshes its view when session_info_update arrives.
        sessions.sendCommand(m, RpcCommands.branch(req.entryId()));
        return Map.of("ok", true, "entryId", req.entryId());
    }

    @PostMapping("/{sessionId}/new-session")
    public JsonNode newSession(@PathVariable String sessionId, @RequestBody(required = false) NewSessionRequest req) throws Exception {
        SessionMeta m = require(sessionId);
        JsonNode result = sessions.sendCommand(m,
                req != null && req.parentSession() != null
                        ? RpcCommands.newSessionFromParent(req.parentSession())
                        : RpcCommands.newSession()).get();
        // omp emits a fresh session id; we don't track it here — the next /state call shows the new id.
        return result;
    }

    @PostMapping("/{sessionId}/compact")
    public JsonNode compact(@PathVariable String sessionId, @RequestBody(required = false) Map<String, String> body) throws Exception {
        SessionMeta m = require(sessionId);
        String instr = body == null ? null : body.get("customInstructions");
        return sessions.sendCommand(m, instr != null ? RpcCommands.compact(instr) : RpcCommands.compact()).get();
    }

    @PostMapping("/{sessionId}/archive")
    public Map<String, Object> archive(@PathVariable String sessionId) {
        SessionMeta m = require(sessionId);
        sessions.archive(sessionId);
        return Map.of("ok", true);
    }

    @PostMapping("/{sessionId}/unarchive")
    public Map<String, Object> unarchive(@PathVariable String sessionId) {
        SessionMeta m = require(sessionId);
        long active = sessions.countActiveByUser(m.getUserId());
        if (active >= props.perUserSessionLimit()) {
            throw new RuntimeException("当前用户会话数已达上限 (" + props.perUserSessionLimit() + ")");
        }
        checkDiskQuotaByUserId(m.getUserId());
        sessions.unarchive(sessionId);
        return Map.of("ok", true);
    }

    /**
     * Resume a previously archived/killed session: unarchive + force a fresh process spawn
     * so the next getMessages() call reads the saved session file (passed as --resume).
     */
    @PostMapping("/{sessionId}/resume")
    public Map<String, Object> resume(@PathVariable String sessionId) throws Exception {
        SessionMeta m = require(sessionId);
        long active = sessions.countActiveByUser(m.getUserId());
        if (active >= props.perUserSessionLimit()) {
            throw new RuntimeException("当前用户会话数已达上限 (" + props.perUserSessionLimit() + ")");
        }
        checkDiskQuotaByUserId(m.getUserId());
        sessions.unarchive(sessionId);
        m.setStatus("active");
        // Kick the pool so the next sendCommand spawns with --resume
        sessions.resumeSession(sessionId);
        return Map.of("ok", true);
    }

    private SessionMeta require(String sessionId) {
        return sessions.findScoped(sessionId, currentUser.scope())
                .orElseThrow(() -> new RuntimeException("会话不存在"));
    }

    /**
     * 按会话归属用户检查磁盘配额，超限时抛出异常阻止操作。
     * 限额 ≤ 0 表示不限制，跳过检查。
     */
    private void checkDiskQuotaByUserId(Long userId) {
        User owner = users.findById(userId).orElse(null);
        if (owner == null || owner.getDiskLimitMb() <= 0) return;
        long usage = workspace.calculateDiskUsage(userId);
        if (usage >= owner.getDiskLimitMb()) {
            throw new RuntimeException(
                    "磁盘空间不足（已用 " + usage + " MB，限额 " + owner.getDiskLimitMb() + " MB），无法操作");
        }
    }

    /**
     * 检查用户大模型 Token 额度。
     * 限额 ≤ 0 表示不限制，跳过检查；已消耗 ≥ 限额时拒绝发送。
     */
    private void checkTokenQuotaByUserId(Long userId) {
        User owner = users.findById(userId).orElse(null);
        if (owner == null || owner.getTokenLimit() <= 0) return;
        if (owner.getTokenUsed() >= owner.getTokenLimit()) {
            throw new IllegalStateException(
                    "Token 额度已用尽（已用 " + owner.getTokenUsed() + "，限额 " + owner.getTokenLimit() + "），无法继续对话");
        }
    }

    /**
     * 检查当前用户磁盘配额。
     * 限额 ≤ 0 表示不限制，跳过检查。
     */
    private void checkDiskQuota(User user) {
        if (user.getDiskLimitMb() <= 0) return;
        long usage = workspace.calculateDiskUsage(user.getId());
        if (usage >= user.getDiskLimitMb()) {
            throw new RuntimeException(
                    "磁盘空间不足（已用 " + usage + " MB，限额 " + user.getDiskLimitMb() + " MB），无法创建会话");
        }
    }

    private Map<String, Object> toDto(SessionMeta m) {
        Map<String, Object> r = new java.util.HashMap<>();
        r.put("sessionId", m.getSessionId());
        r.put("repoId", m.getRepoId());
        r.put("title", m.getTitle() == null ? "" : m.getTitle());
        r.put("status", m.getStatus());
        r.put("ompSessionFile", m.getOmpSessionFile() == null ? "" : m.getOmpSessionFile());
        r.put("createdAt", m.getCreatedAt() == null ? null : m.getCreatedAt().toString());
        r.put("lastActiveAt", m.getLastActiveAt() == null ? null : m.getLastActiveAt().toString());
        return r;
    }
}