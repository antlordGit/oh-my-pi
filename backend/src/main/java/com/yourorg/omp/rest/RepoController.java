package com.yourorg.omp.rest;

import com.yourorg.omp.entity.Repo;
import com.yourorg.omp.entity.User;
import com.yourorg.omp.repo.RepoRepository;
import com.yourorg.omp.security.CurrentUser;
import com.yourorg.omp.workspace.WorkspaceService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
    public record CloneRequest(String repoId, String url, String branch, Integer depth, String username, String password) {}
    public record InitRequest(String repoId, String template, String displayName) {}

    /** git clone URL 允许的 scheme 白名单 */
    private static final Set<String> ALLOWED_CLONE_SCHEMES = Set.of("http", "https", "git");

    /** 克隆仓库：把远程仓库通过 {@code git clone} 拉取到用户工作区。 */
    @PostMapping("/clone")
    public Map<String, Object> clone(@RequestBody CloneRequest req) {
        var self = currentUser.require();
        Long uid = self.getId();
        validateRepoIdOrThrow(req.repoId(), "仓库标识");
        // 验证不含凭证的基础 URL
        String baseUrl = stripCredentials(req.url());
        validateCloneUrl(baseUrl);
        if (repo.findByUserIdAndRepoId(uid, req.repoId()).isPresent()) {
            throw new IllegalArgumentException("仓库已存在");
        }
        checkDiskQuota(self);

        // 组装含凭证的 URL：https://user:pass@host/repo.git
        String authUrl = baseUrl;
        if (req.username() != null && !req.username().isBlank()) {
            String user = encodeUriComponent(req.username());
            String pass = req.password() != null ? ":" + encodeUriComponent(req.password()) : "";
            URI u = URI.create(baseUrl);
            String scheme = u.getScheme();
            String auth = user + pass + "@";
            String rebuilt = scheme + "://" + auth + u.getAuthority() + u.getPath();
            if (u.getQuery() != null) rebuilt += "?" + u.getQuery();
            if (u.getFragment() != null) rebuilt += "#" + u.getFragment();
            authUrl = rebuilt;
        }

        Repo r = new Repo();
        r.setUserId(uid);
        r.setTenantId(self.getTenantId());
        r.setRepoId(req.repoId());
        r.setDisplayName(req.repoId());
        r = repo.save(r);

        try {
            workspace.cloneRepository(uid, req.repoId(), authUrl, req.branch(), req.depth());
        } catch (RuntimeException e) {
            rollbackRepoCreation(uid, req.repoId(), r);
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        return toDto(r);
    }

    /**
     * 初始化模板：把 classpath 下预置的前端 / 后端模板拷贝到用户工作区，
     * 然后 git init 把模板作为首次 commit。
     */
    @PostMapping("/init")
    public Map<String, Object> init(@RequestBody InitRequest req) {
        var self = currentUser.require();
        Long uid = self.getId();
        validateRepoIdOrThrow(req.repoId(), "仓库标识");
        if (req.template() == null
                || !(req.template().equals("frontend") || req.template().equals("backend"))) {
            throw new IllegalArgumentException("不支持的模板: " + req.template());
        }
        if (repo.findByUserIdAndRepoId(uid, req.repoId()).isPresent()) {
            throw new IllegalArgumentException("仓库已存在");
        }
        checkDiskQuota(self);

        Repo r = new Repo();
        r.setUserId(uid);
        r.setTenantId(self.getTenantId());
        r.setRepoId(req.repoId());
        r.setDisplayName(req.displayName() == null ? req.repoId() : req.displayName());
        r = repo.save(r);

        try {
            workspace.initFromTemplate(uid, req.repoId(), req.template());
        } catch (RuntimeException e) {
            rollbackRepoCreation(uid, req.repoId(), r);
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        return toDto(r);
    }

    /**
     * 克隆 / 模板初始化失败时的回滚：
     * 1. 删半成品工作区目录（容忍"目录不存在"）
     * 2. 删 DB Repo 行
     */
    private void rollbackRepoCreation(Long userId, String repoId, Repo r) {
        try {
            workspace.deleteWorkspace(userId, repoId);
        } catch (Exception ignored) {
            // deleteWorkspace 在目录不存在时会抛 IllegalArgumentException，忽略
        }
        try {
            repo.delete(r);
        } catch (Exception ignored) {
            // 回滚 DB 失败只能记日志；事务边界外无法影响已 commit 的 Repo 行
        }
    }

    private void validateRepoIdOrThrow(String repoId, String label) {
        if (repoId == null || repoId.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (!REPO_ID_PATTERN.matcher(repoId).matches()) {
            throw new IllegalArgumentException(label + "只能包含英文、数字和-，且不超过32位");
        }
    }

    /**
     * git clone URL 校验：
     * 1. scheme 必须是 http / https / git
     * 2. host 不能解析到 loopback / 私网 / link-local / 通配地址
     * 3. 字面拦截 localhost / 127. 字面
     */
    private void validateCloneUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Git URL 不能为空");
        }
        URI u;
        try {
            u = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Git URL 格式非法: " + e.getMessage());
        }
        String scheme = u.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("Git URL 缺少协议: " + url);
        }
        scheme = scheme.toLowerCase();
        if (!ALLOWED_CLONE_SCHEMES.contains(scheme)) {
            throw new IllegalArgumentException("不支持的协议: " + scheme);
        }
        String host = u.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Git URL 缺少主机名");
        }
        // 字面兜底：防 DNS 不解析时绕开
        String lower = host.toLowerCase();
        if (lower.contains("localhost") || lower.contains("127.")) {
            throw new IllegalArgumentException("不允许的 Git 主机: " + host);
        }
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            for (InetAddress addr : addrs) {
                if (addr.isLoopbackAddress()
                        || addr.isLinkLocalAddress()
                        || addr.isAnyLocalAddress()) {
                    throw new IllegalArgumentException("不允许的 Git 主机: " + host);
                }
            }
        } catch (IllegalArgumentException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalArgumentException("Git 主机解析失败: " + host + " (" + e.getMessage() + ")");
        }
    }

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
        checkDiskQuota(self);
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
     * 导入工程：将本地文件夹上传为仓库，文件夹名自动作为仓库标识。
     * 前端通过 webkitdirectory 选择文件夹，所有文件以相对路径保留目录结构。
     */
    @PostMapping("/import")
    public Map<String, Object> importRepo(
            @RequestParam String repoId,
            @RequestParam("files") List<MultipartFile> files) throws IOException {
        var self = currentUser.require();
        Long uid = self.getId();

        // 验证仓库标识规则
        if (repoId == null || repoId.isBlank()) {
            throw new IllegalArgumentException("仓库标识不能为空");
        }
        if (!REPO_ID_PATTERN.matcher(repoId).matches()) {
            throw new IllegalArgumentException("仓库标识只能包含英文、数字和-，且不超过32位");
        }
        if (repo.findByUserIdAndRepoId(uid, repoId).isPresent()) {
            throw new IllegalArgumentException("仓库已存在");
        }
        checkDiskQuota(self);

        // 创建仓库记录
        Repo r = new Repo();
        r.setUserId(uid);
        r.setTenantId(self.getTenantId());
        r.setRepoId(repoId);
        r.setDisplayName(repoId);
        r = repo.save(r);

        // 创建工作区目录
        Path workspacePath = workspace.userWorkspace(uid, r.getRepoId());

        // 写入所有上传的文件，保留目录结构
        for (MultipartFile file : files) {
            String originalName = file.getOriginalFilename();
            if (originalName == null || originalName.isBlank()) continue;
            Path dest = workspacePath.resolve(originalName).normalize();
            // 安全检查：确保目标在工作区内
            if (!dest.startsWith(workspacePath)) {
                continue;
            }
            Files.createDirectories(dest.getParent());
            file.transferTo(dest.toFile());
        }

        return toDto(r);
    }

    /**
     * 导出仓库：将工作区文件压缩为 zip 并下载，文件名格式为 {repoId}.zip。
     */
    @GetMapping("/{repoId}/export")
    public void exportRepo(@PathVariable String repoId, HttpServletResponse response) throws IOException {
        var scope = currentUser.scope();
        Repo r = repo.findScopedByRepoId(repoId, scope.userId(), scope.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("仓库不存在或无访问权限"));

        Path workspacePath = workspace.userWorkspace(r.getUserId(), repoId);

        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + repoId + ".zip\"");

        try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {
            try (var walk = Files.walk(workspacePath)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> !p.startsWith(workspacePath.resolve(".git")))
                    .forEach(p -> {
                        String entryName = workspacePath.relativize(p).toString();
                        try {
                            zos.putNextEntry(new ZipEntry(entryName));
                            Files.copy(p, zos);
                            zos.closeEntry();
                        } catch (IOException e) {
                            throw new RuntimeException("压缩失败: " + entryName, e);
                        }
                    });
            } catch (RuntimeException e) {
                if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
                throw e;
            }
        }
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

    /**
     * 检查用户磁盘配额，超限则抛出异常阻止创建/导入。
     * 限额 ≤ 0 表示不限制，跳过检查。
     */
    private void checkDiskQuota(User user) {
        if (user.getDiskLimitMb() <= 0) return;
        long usage = workspace.calculateDiskUsage(user.getId());
        if (usage >= user.getDiskLimitMb()) {
            throw new IllegalArgumentException(
                    "磁盘空间不足（已用 " + usage + " MB，限额 " + user.getDiskLimitMb() + " MB），无法创建仓库");
        }
    }

    private Map<String, Object> toDto(Repo r) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", r.getId());
        m.put("repoId", r.getRepoId());
        m.put("displayName", r.getDisplayName());
        m.put("createdAt", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
        return m;
    }

    /** 从 URL 中剥离可能已有的 user:pass 认证信息。 */
    private static String stripCredentials(String url) {
        try {
            URI u = URI.create(url);
            if (u.getUserInfo() == null) return url;
            String stripped = u.getScheme() + "://" + u.getHost();
            if (u.getPort() > 0) stripped += ":" + u.getPort();
            stripped += u.getPath();
            if (u.getQuery() != null) stripped += "?" + u.getQuery();
            if (u.getFragment() != null) stripped += "#" + u.getFragment();
            return stripped;
        } catch (Exception e) {
            return url;
        }
    }

    /** 对用户名 / 密码做 URI 编码，防止特殊字符破坏 URL 结构。 */
    private static String encodeUriComponent(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A");
    }
}