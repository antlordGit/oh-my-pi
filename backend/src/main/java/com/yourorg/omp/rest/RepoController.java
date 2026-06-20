package com.yourorg.omp.rest;

import com.yourorg.omp.entity.Repo;
import com.yourorg.omp.repo.RepoRepository;
import com.yourorg.omp.security.CurrentUser;
import com.yourorg.omp.workspace.WorkspaceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/repos")
public class RepoController {

    /** repoId must be alphanumeric or hyphen, max 32 chars. */
    private static final Pattern REPO_ID_PATTERN = Pattern.compile("^[A-Za-z0-9-]{1,32}$");

    private final RepoRepository repo;
    private final WorkspaceService workspace;
    private final CurrentUser currentUser;

    public RepoController(RepoRepository repo, WorkspaceService workspace, CurrentUser currentUser) {
        this.repo = repo;
        this.workspace = workspace;
        this.currentUser = currentUser;
    }

    public record CreateRepoRequest(String repoId, String displayName) {}
    public record CopyRepoRequest(String targetRepoId, String displayName) {}

    @GetMapping
    public List<Map<String, Object>> list() {
        return repo.findScoped(currentUser.scope().userId(), currentUser.scope().tenantId())
                .stream().map(this::toDto).toList();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateRepoRequest req) {
        var self = currentUser.require();
        Long uid = self.getId();
        if (req.repoId() == null || req.repoId().isBlank()) {
            throw new IllegalArgumentException("仓库标识不能为空");
        }
        if (!REPO_ID_PATTERN.matcher(req.repoId()).matches()) {
            throw new IllegalArgumentException("仓库标识只能包含英文、数字和-，且不超过32位");
        }
        if (repo.findByUserIdAndRepoId(uid, req.repoId()).isPresent()) {
            throw new IllegalArgumentException("仓库已存在");
        }
        Repo r = new Repo();
        r.setUserId(uid);
        r.setTenantId(self.getTenantId());
        r.setRepoId(req.repoId());
        r.setDisplayName(req.displayName() == null ? req.repoId() : req.displayName());
        r = repo.save(r);
        // Trigger workspace init (also runs git init).
        workspace.userWorkspace(uid, r.getRepoId());
        return toDto(r);
    }

    /**
     * Copy a repo and all its files to a new repoId.
     * Admin and super_admin can perform this operation within their data scope.
     */
    @PostMapping("/{repoId}/copy")
    public Map<String, Object> copy(@PathVariable String repoId, @RequestBody CopyRepoRequest req) {
        var self = currentUser.require();
        if (!self.isAdmin()) {
            throw new IllegalArgumentException("只有管理员可以复制仓库");
        }

        if (req.targetRepoId() == null || req.targetRepoId().isBlank()) {
            throw new IllegalArgumentException("目标仓库标识不能为空");
        }
        if (!REPO_ID_PATTERN.matcher(req.targetRepoId()).matches()) {
            throw new IllegalArgumentException("仓库标识只能包含英文、数字和-，且不超过32位");
        }

        // Verify source repo exists and user has access (within data scope)
        var scope = currentUser.scope();
        Repo sourceRepo = repo.findScopedByRepoId(repoId, scope.userId(), scope.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("仓库不存在或无访问权限"));

        // Check if target repoId already exists in user's scope
        if (repo.findByUserIdAndRepoId(self.getId(), req.targetRepoId()).isPresent()) {
            throw new IllegalArgumentException("目标仓库已存在");
        }

        // Copy workspace files (use source repo's owner for workspace path)
        Long ownerId = sourceRepo.getUserId();
        workspace.copyWorkspace(ownerId, repoId, req.targetRepoId());

        // Create DB record - new repo belongs to current user
        Repo r = new Repo();
        r.setUserId(self.getId());
        r.setTenantId(self.getTenantId());
        r.setRepoId(req.targetRepoId());
        r.setDisplayName(req.displayName() == null ? req.targetRepoId() : req.displayName());
        r = repo.save(r);

        return toDto(r);
    }

    /**
     * Delete a repo and all its files. Irreversible.
     * Admin and super_admin can perform this operation within their data scope.
     */
    @DeleteMapping("/{repoId}")
    public Map<String, Object> delete(@PathVariable String repoId) {
        var self = currentUser.require();
        if (!self.isAdmin()) {
            throw new IllegalArgumentException("只有管理员可以删除仓库");
        }

        // Verify repo exists within user's data scope
        var scope = currentUser.scope();
        Repo r = repo.findScopedByRepoId(repoId, scope.userId(), scope.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("仓库不存在或无访问权限"));

        // Delete workspace files (use repo's owner for workspace path)
        workspace.deleteWorkspace(r.getUserId(), repoId);

        // Delete DB record
        repo.delete(r);

        return Map.of("ok", true, "repoId", repoId);
    }

    @GetMapping("/{repoId}/files")
    public List<String> files(@PathVariable String repoId) throws Exception {
        return workspace.listFiles(resolveOwnerId(repoId), repoId);
    }

    /**
     * Tree view of the workspace, used by the chat page right sidebar.
     * {@code depth} is the number of directory levels to include (1 = root only).
     * Hidden directories (.git, node_modules, etc.) are skipped server-side.
     */
    @GetMapping("/{repoId}/tree")
    public List<com.yourorg.omp.workspace.WorkspaceService.TreeNode> tree(
            @PathVariable String repoId,
            @RequestParam(defaultValue = "2") int depth) throws Exception {
        return workspace.listTree(resolveOwnerId(repoId), repoId, depth);
    }

    @GetMapping("/{repoId}/file")
    public Map<String, String> getFile(@PathVariable String repoId, @RequestParam String path) throws Exception {
        return Map.of("path", path, "content", workspace.readFile(resolveOwnerId(repoId), repoId, path));
    }

    /** Write back a file into the workspace, guarded by boundary checks. */
    @PutMapping("/{repoId}/file")
    public Map<String, Object> writeFile(
            @PathVariable String repoId,
            @RequestParam String path,
            @RequestBody Map<String, String> body) throws Exception {
        String content = body.get("content");
        if (content == null) throw new IllegalArgumentException("content is required");
        workspace.writeFile(resolveOwnerId(repoId), repoId, path, content);
        return Map.of("ok", true, "path", path);
    }

    @GetMapping("/{repoId}/diff")
    public Map<String, String> diff(@PathVariable String repoId, @RequestParam String refA, @RequestParam String refB) {
        return Map.of("diff", workspace.diff(resolveOwnerId(repoId), repoId, refA, refB));
    }

    @GetMapping("/{repoId}/log")
    public Map<String, String> log(@PathVariable String repoId, @RequestParam(defaultValue = "20") int n) {
        return Map.of("log", workspace.log(resolveOwnerId(repoId), repoId, n));
    }

    /**
     * 在当前用户数据范围内解析仓库归属用户 ID。
     * 普通用户只能命中自己的仓库；管理员可命中本租户任意用户的仓库；超管不限。
     * 工作区目录按 owner 的 userId 定位，因此返回真实归属用户而非登录用户。
     */
    private Long resolveOwnerId(String repoId) {
        var scope = currentUser.scope();
        Repo r = repo.findScopedByRepoId(repoId, scope.userId(), scope.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("仓库不存在或无访问权限"));
        return r.getUserId();
    }

    private Map<String, Object> toDto(Repo r) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", r.getId());
        m.put("repoId", r.getRepoId());
        m.put("displayName", r.getDisplayName());
        m.put("createdAt", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
        return m;
    }
}