package com.yourorg.omp.rest;

import com.yourorg.omp.entity.Repo;
import com.yourorg.omp.repo.RepoRepository;
import com.yourorg.omp.security.CurrentUser;
import com.yourorg.omp.workspace.WorkspaceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/repos")
public class RepoController {

    private final RepoRepository repo;
    private final WorkspaceService workspace;
    private final CurrentUser currentUser;

    public RepoController(RepoRepository repo, WorkspaceService workspace, CurrentUser currentUser) {
        this.repo = repo;
        this.workspace = workspace;
        this.currentUser = currentUser;
    }

    public record CreateRepoRequest(String repoId, String displayName) {}

    @GetMapping
    public List<Map<String, Object>> list() {
        Long uid = currentUser.requireId();
        return repo.findByUserIdOrderByCreatedAtDesc(uid).stream().map(this::toDto).toList();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateRepoRequest req) {
        Long uid = currentUser.requireId();
        if (req.repoId() == null || req.repoId().isBlank()) {
            throw new IllegalArgumentException("repoId required");
        }
        if (repo.findByUserIdAndRepoId(uid, req.repoId()).isPresent()) {
            throw new IllegalArgumentException("repo already exists");
        }
        Repo r = new Repo();
        r.setUserId(uid);
        r.setRepoId(req.repoId());
        r.setDisplayName(req.displayName() == null ? req.repoId() : req.displayName());
        r = repo.save(r);
        // Trigger workspace init (also runs git init).
        workspace.userWorkspace(uid, r.getRepoId());
        return toDto(r);
    }

    @GetMapping("/{repoId}/files")
    public List<String> files(@PathVariable String repoId) throws Exception {
        Long uid = currentUser.requireId();
        return workspace.listFiles(uid, repoId);
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
        Long uid = currentUser.requireId();
        return workspace.listTree(uid, repoId, depth);
    }

    @GetMapping("/{repoId}/file")
    public Map<String, String> getFile(@PathVariable String repoId, @RequestParam String path) throws Exception {
        Long uid = currentUser.requireId();
        return Map.of("path", path, "content", workspace.readFile(uid, repoId, path));
    }

    /** Write back a file into the workspace, guarded by boundary checks. */
    @PutMapping("/{repoId}/file")
    public Map<String, Object> writeFile(
            @PathVariable String repoId,
            @RequestParam String path,
            @RequestBody Map<String, String> body) throws Exception {
        Long uid = currentUser.requireId();
        String content = body.get("content");
        if (content == null) throw new IllegalArgumentException("content is required");
        workspace.writeFile(uid, repoId, path, content);
        return Map.of("ok", true, "path", path);
    }

    @GetMapping("/{repoId}/diff")
    public Map<String, String> diff(@PathVariable String repoId, @RequestParam String refA, @RequestParam String refB) {
        Long uid = currentUser.requireId();
        return Map.of("diff", workspace.diff(uid, repoId, refA, refB));
    }

    @GetMapping("/{repoId}/log")
    public Map<String, String> log(@PathVariable String repoId, @RequestParam(defaultValue = "20") int n) {
        Long uid = currentUser.requireId();
        return Map.of("log", workspace.log(uid, repoId, n));
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