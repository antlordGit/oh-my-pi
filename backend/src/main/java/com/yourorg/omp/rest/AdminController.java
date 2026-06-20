package com.yourorg.omp.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.yourorg.omp.admin.AdminConfigService;
import com.yourorg.omp.audit.AuditService;
import com.yourorg.omp.pool.ProcessPool;
import com.yourorg.omp.repo.PromptAuditRepository;
import com.yourorg.omp.repo.ResponseAuditRepository;
import com.yourorg.omp.repo.SessionMetaRepository;
import com.yourorg.omp.repo.ToolAuditRepository;
import com.yourorg.omp.repo.UserRepository;
import com.yourorg.omp.security.JwtService;
import com.yourorg.omp.entity.User;
import com.yourorg.omp.session.SessionManager;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminController {

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

    public AdminController(AdminConfigService config, UserRepository users, JwtService jwt,
                           SessionMetaRepository sessions, SessionManager sessionManager, ProcessPool pool,
                           PromptAuditRepository prompts, ToolAuditRepository tools,
                           ResponseAuditRepository responses, AuditService audit) {
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
        return users.findAll().stream().map(u -> Map.of(
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
            throw new IllegalArgumentException("User already exists");
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
    public Object listSessions() {
        return sessions.findAll().stream().map(m -> {
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
    }

    @PostMapping("/sessions/{id}/kill")
    public Map<String, Object> killSession(@PathVariable String id) {
        // Mirror SessionManager.archive: persist status=archived AND evict the live process.
        // ProcessPool alone leaves the DB row as "active", so the next listSessions() would
        // mask the kill. archive() does both in one transaction.
        sessionManager.archive(id);
        return Map.of("ok", true);
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

    @GetMapping("/audit/prompts")
    public Object auditPrompts(@RequestParam(required = false) String sessionId,
                               @RequestParam(required = false) Long userId,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "50") int size) {
        return prompts.search(sessionId, userId, PageRequest.of(page, size)).getContent();
    }

    @GetMapping("/audit/tools")
    public Object auditTools(@RequestParam(required = false) String sessionId,
                             @RequestParam(required = false) Long userId,
                             @RequestParam(required = false) String toolName,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "50") int size) {
        return tools.search(sessionId, userId, toolName, PageRequest.of(page, size)).getContent();
    }

    @GetMapping("/audit/responses")
    public Object auditResponses(@RequestParam(required = false) String sessionId,
                                 @RequestParam(required = false) Long userId,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "50") int size) {
        return responses.search(sessionId, userId, PageRequest.of(page, size)).getContent();
    }

    // ---- pool stats ----

    @GetMapping("/pool")
    public Map<String, Object> poolStats() {
        return Map.of(
                "active", pool.activeCount(),
                "usedSlots", pool.usedSlots()
        );
    }
}