package com.yourorg.omp.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.yourorg.omp.admin.AdminConfigService;
import com.yourorg.omp.audit.AuditService;
import com.yourorg.omp.config.OmpProperties;
import com.yourorg.omp.pool.ProcessPool;
import com.yourorg.omp.repo.PromptAuditRepository;
import com.yourorg.omp.repo.ResponseAuditRepository;
import com.yourorg.omp.repo.SessionMetaRepository;
import com.yourorg.omp.repo.ToolAuditRepository;
import com.yourorg.omp.repo.UserRepository;
import com.yourorg.omp.security.JwtService;
import com.yourorg.omp.security.CurrentUser;
import com.yourorg.omp.entity.User;
import com.yourorg.omp.session.SessionManager;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private final AdminConfigService config;
    private final UserRepository users;
    private final JwtService jwt;
    private final SessionMetaRepository sessions;
    private final SessionManager sessionManager;
    private final ProcessPool pool;
    private final PromptAuditRepository prompts;
    private final ToolAuditRepository tools;
    private final ResponseAuditRepository responses;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final com.yourorg.omp.maintenance.MaintenanceService maintenance;
    private final OmpProperties props;
    private static final Set<String> ALLOWED_SYSTEM_FILES = Set.of("mcp.json", "APPEND_SYSTEM.md");

    public AdminController(AdminConfigService config, UserRepository users, JwtService jwt,
                           SessionMetaRepository sessions, SessionManager sessionManager, ProcessPool pool,
                           PromptAuditRepository prompts, ToolAuditRepository tools,
                           ResponseAuditRepository responses, AuditService audit,
                           CurrentUser currentUser,
                           com.yourorg.omp.maintenance.MaintenanceService maintenance,
                           OmpProperties props) {
        this.config = config;
        this.users = users;
        this.jwt = jwt;
        this.sessions = sessions;
        this.sessionManager = sessionManager;
        this.pool = pool;
        this.prompts = prompts;
        this.tools = tools;
        this.responses = responses;
        this.audit = audit;
        this.currentUser = currentUser;
        this.maintenance = maintenance;
        this.props = props;
    }

    // ---- config ----

    @GetMapping("/config")
    public Map<String, JsonNode> getConfig() {
        return config.getAll();
    }

    @PutMapping("/config/{key}")
    public Map<String, Object> setConfig(@PathVariable String key, @RequestBody Map<String, Object> body) {
        Object value = body.get("value");
        String desc = (String) body.getOrDefault("description", null);
        config.set(key, value, desc, "admin");
        return Map.of("ok", true, "key", key);
    }

    @DeleteMapping("/config/{key}")
    public Map<String, Object> deleteConfig(@PathVariable String key) {
        config.delete(key);
        return Map.of("ok", true);
    }

    // ---- users ----

    public record CreateUserRequest(@NotBlank String username, @NotBlank String password, String role) {}

    @GetMapping("/users")
    public Object listUsers() {
        Long tenantId = currentUser.scope().tenantId();
        return users.findAll().stream()
                .filter(u -> tenantId == null || tenantId.equals(u.getTenantId()))
                .map(u -> Map.of(
                "id", u.getId(),
                "username", u.getUsername(),
                "role", u.getRole(),
                "enabled", u.isEnabled(),
                "createdAt", u.getCreatedAt() == null ? null : u.getCreatedAt().toString(),
                "lastLoginAt", u.getLastLoginAt() == null ? null : u.getLastLoginAt().toString()
        )).toList();
    }

    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody CreateUserRequest req) {
        if (users.existsByUsername(req.username())) {
            throw new IllegalArgumentException("用户名已存在");
        }
        User u = new User();
        u.setUsername(req.username());
        u.setPasswordHash(jwt.encoder().encode(req.password()));
        u.setRole(req.role() == null ? "user" : req.role());
        u.setEnabled(true);
        users.save(u);
        return Map.of("id", u.getId(), "username", u.getUsername(), "role", u.getRole());
    }

    @PostMapping("/users/{id}/disable")
    public Map<String, Object> disable(@PathVariable Long id) {
        users.findById(id).ifPresent(u -> { u.setEnabled(false); users.save(u); });
        return Map.of("ok", true);
    }

    @PostMapping("/users/{id}/enable")
    public Map<String, Object> enable(@PathVariable Long id) {
        users.findById(id).ifPresent(u -> { u.setEnabled(true); users.save(u); });
        return Map.of("ok", true);
    }

    // ---- sessions ----

    @GetMapping("/sessions")
    public Object listSessions(@RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        Page<com.yourorg.omp.entity.SessionMeta> p = sessions.findScopedPaged(
                currentUser.scope().userId(),
                currentUser.scope().tenantId(),
                PageRequest.of(page, size));
        List<Map<String, Object>> items = p.getContent().stream().map(m -> {
            // processAlive reflects the live OmpRpcClient in ProcessPool (not the DB column).
            // effectiveStatus prefers the live state for admin visibility, then falls back to DB.
            // DB status is kept untouched so archive/unarchive flows still see the persisted value.
            boolean processAlive = pool.isActive(m.getSessionId());
            String effectiveStatus = processAlive ? "active" : m.getStatus();
            return Map.<String, Object>of(
                    "sessionId", m.getSessionId(),
                    "userId", m.getUserId(),
                    "repoId", m.getRepoId(),
                    "status", m.getStatus(),
                    "processAlive", processAlive,
                    "effectiveStatus", effectiveStatus,
                    "title", m.getTitle() == null ? "" : m.getTitle(),
                    "lastActiveAt", m.getLastActiveAt() == null ? null : m.getLastActiveAt().toString()
            );
        }).toList();
        return Map.of(
                "items", items,
                "total", p.getTotalElements(),
                "page", p.getNumber(),
                "size", p.getSize()
        );
    }

    @PostMapping("/sessions/{id}/kill")
    public Map<String, Object> killSession(@PathVariable String id) {
        // Mirror SessionManager.archive: persist status=archived AND evict the live process.
        // ProcessPool alone leaves the DB row as "active", so the next listSessions() would
        // mask the kill. archive() does both in one transaction.
        sessionManager.archive(id);
        return Map.of("ok", true);
    }

    /**
     * 彻底删除一个已归档会话：DB 行 + 三张审计表 + ompSessionFile 磁盘文件。
     * 仅接受 status=archived 的会话；其他状态返回 400。
     */
    @PostMapping("/sessions/{id}/delete")
    public Map<String, Object> deleteSession(@PathVariable String id) {
        try {
            boolean ok = sessionManager.delete(id);
            if (!ok) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在：" + id);
            }
            return Map.of("ok", true);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/sessions/{id}/reload")
    public Map<String, Object> reloadSession(@PathVariable String id) {
        pool.evict(id);
        sessionManager.find(id).ifPresent(m -> {
            // Acquire will spawn a fresh process; audit will re-attach.
            audit.attach(id);
            // Touching via process is implicit on next prompt.
        });
        return Map.of("ok", true);
    }

    // ---- audit ----

    /** Unified audit API: merges prompts, tools, responses into one paged list sorted by time. */
    @GetMapping("/audit")
    public Object auditUnified(@RequestParam(required = false) String sessionId,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "50") int size) {
        Long tenantId = currentUser.scope().tenantId();

        // Fetch enough data from each table for merging (memory-based approach)
        int fetchSize = Math.max(size * 3, 100);
        List<com.yourorg.omp.entity.PromptAudit> pList = prompts.search(sessionId, null, tenantId,
                PageRequest.of(0, fetchSize)).getContent();
        List<com.yourorg.omp.entity.ToolAudit> tList = tools.search(sessionId, null, tenantId, null,
                PageRequest.of(0, fetchSize)).getContent();
        List<com.yourorg.omp.entity.ResponseAudit> rList = responses.search(sessionId, null, tenantId,
                PageRequest.of(0, fetchSize)).getContent();

        // Merge into unified structure
        List<Map<String, Object>> all = new ArrayList<>();
        for (com.yourorg.omp.entity.PromptAudit p : pList) {
            all.add(Map.<String, Object>of(
                    "_type", "prompt",
                    "_time", p.getSentAt(),
                    "id", p.getId(),
                    "sessionId", p.getSessionId(),
                    "userId", p.getUserId(),
                    "promptText", p.getPromptText() == null ? "" : p.getPromptText()
            ));
        }
        for (com.yourorg.omp.entity.ToolAudit t : tList) {
            all.add(Map.<String, Object>of(
                    "_type", "tool",
                    "_time", t.getStartedAt(),
                    "id", t.getId(),
                    "sessionId", t.getSessionId(),
                    "userId", t.getUserId(),
                    "toolName", t.getToolName(),
                    "arguments", t.getArguments() == null ? "" : t.getArguments(),
                    "isError", t.isError()
            ));
        }
        for (com.yourorg.omp.entity.ResponseAudit r : rList) {
            all.add(Map.<String, Object>of(
                    "_type", "response",
                    "_time", r.getFinishedAt(),
                    "id", r.getId(),
                    "sessionId", r.getSessionId(),
                    "userId", r.getUserId(),
                    "fullText", r.getFullText() == null ? "" : r.getFullText(),
                    "isError", r.isError(),
                    "stopReason", r.getStopReason() == null ? "" : r.getStopReason()
            ));
        }

        // Sort by time descending
        all.sort((a, b) -> {
            Instant ta = (Instant) a.get("_time");
            Instant tb = (Instant) b.get("_time");
            return tb.compareTo(ta);
        });

        // Manual pagination
        int from = page * size;
        int to = Math.min(from + size, all.size());
        List<Map<String, Object>> items = from < all.size() ? all.subList(from, to) : List.of();

        return Map.of(
                "items", items,
                "total", all.size(),
                "page", page,
                "size", size
        );
    }

    @GetMapping("/audit/prompts")
    public Object auditPrompts(@RequestParam(required = false) String sessionId,
                               @RequestParam(required = false) Long userId,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "50") int size) {
        return prompts.search(sessionId, userId, currentUser.scope().tenantId(),
                PageRequest.of(page, size)).getContent();
    }

    @GetMapping("/audit/tools")
    public Object auditTools(@RequestParam(required = false) String sessionId,
                             @RequestParam(required = false) Long userId,
                             @RequestParam(required = false) String toolName,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "50") int size) {
        return tools.search(sessionId, userId, currentUser.scope().tenantId(), toolName,
                PageRequest.of(page, size)).getContent();
    }

    @GetMapping("/audit/responses")
    public Object auditResponses(@RequestParam(required = false) String sessionId,
                                 @RequestParam(required = false) Long userId,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "50") int size) {
        return responses.search(sessionId, userId, currentUser.scope().tenantId(),
                PageRequest.of(page, size)).getContent();
    }

    // ---- pool stats ----

    @GetMapping("/pool")
    public Map<String, Object> poolStats() {
        return Map.of(
                "active", pool.activeCount(),
                "usedSlots", pool.usedSlots()
        );
    }

    // ---- maintenance ----

    /** 查询当前维护状态。返回 enabled=false 表示未开启。 */
    @GetMapping("/maintenance/status")
    public Map<String, Object> maintenanceStatus() {
        Map<String, Object> s = maintenance.getStatus();
        if (s == null) {
            return Map.of("enabled", false);
        }
        return s;
    }

    /** 开启系统维护模式（7天 TTL）。开启后新会话/新对话等请求将被拒绝。 */
    @PostMapping("/maintenance/enable")
    public Map<String, Object> enableMaintenance() {
        String operator = currentUser.require().getUsername();
        maintenance.enable(operator);
        return Map.of(
                "ok", true,
                "status", maintenance.getStatus()
        );
    }

    /** 解除系统维护模式。 */
    @PostMapping("/maintenance/disable")
    public Map<String, Object> disableMaintenance() {
        maintenance.disable();
        return Map.of("ok", true);
    }

    /** 查询当前正在推流的会话列表。用于判断「能否执行服务更新」。 */
    @GetMapping("/maintenance/streaming-sessions")
    public Map<String, Object> streamingSessions() {
        List<Map<String, Object>> items = maintenance.getStreamingSessions();
        return Map.of(
                "items", items,
                "total", items.size(),
                "canStop", items.isEmpty()
        );
    }

    // ---- system-files ----

    /** 列出允许通过此接口编辑的系统文件名 */
    @GetMapping("/system-files")
    public Map<String, Object> listSystemFiles() {
        return Map.of(
                "agentRoot", props.agentRoot().toString(),
                "files", ALLOWED_SYSTEM_FILES.stream()
                        .map(name -> Map.of(
                                "name", name,
                                "path", props.agentRoot().resolve(name).toString()
                        ))
                        .toList()
        );
    }

    /** 读取 agent-root 下的系统文件内容 */
    @GetMapping("/system-files/{filename}")
    public Map<String, Object> readSystemFile(@PathVariable String filename) {
        if (!ALLOWED_SYSTEM_FILES.contains(filename)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的文件: " + filename);
        }
        Path file = props.agentRoot().resolve(filename);
        try {
            if (!Files.isRegularFile(file)) {
                return Map.of("name", filename, "path", file.toString(), "content", "", "exists", false);
            }
            String content = Files.readString(file);
            return Map.of("name", filename, "path", file.toString(), "content", content, "exists", true);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取失败: " + e.getMessage());
        }
    }

    /** 写入 agent-root 下的系统文件内容 */
    @PutMapping("/system-files/{filename}")
    public Map<String, Object> writeSystemFile(@PathVariable String filename, @RequestBody Map<String, String> body) {
        if (!ALLOWED_SYSTEM_FILES.contains(filename)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的文件: " + filename);
        }
        String content = body.get("content");
        if (content == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content is required");
        Path file = props.agentRoot().resolve(filename);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            log.info("Admin wrote system file: {} ({} bytes)", filename, content.length());
            return Map.of("ok", true, "name", filename, "path", file.toString());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "写入失败: " + e.getMessage());
        }
    }
}