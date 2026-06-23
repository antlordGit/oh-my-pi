package com.yourorg.omp.workspace;

import com.yourorg.omp.config.OmpProperties;
import com.yourorg.omp.entity.User;
import com.yourorg.omp.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Owns on-disk layout for user code and per-user omp state.
 *
 * <pre>
 *   {workspacesRoot}/{username}/{repoId}/         ← user code (git-initialized)
 *   {agentRoot}/{username}/                       ← PI_CODING_AGENT_DIR
 *   {agentRoot}/{username}/sessions/...           ← omp's JSONL session files
 * </pre>
 *
 * <p>Repo directories are auto-created and git-initialized on first access. Git is the source of truth
 * for file-level history; session JSONL is the source of truth for conversation history.
 *
 * <p>Both {@code username} and {@code repoId} are validated before path construction to prevent
 * directory traversal attacks. Only alphanumeric characters and hyphens are allowed.
 */
@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);
    private static final String GIT_AUTHOR_NAME = "omp";
    private static final String GIT_AUTHOR_EMAIL = "omp@system.local";

    /** Path-safe identifier pattern: alphanumeric and hyphen only. */
    private static final Pattern PATH_ID_PATTERN = Pattern.compile("^[A-Za-z0-9-]+$");

    /** Path-safe username pattern: alphanumeric, hyphen and underscore (下划线对路径安全无害)。 */
    private static final Pattern USERNAME_PATH_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");

    private final OmpProperties props;
    private final UserRepository users;

    public WorkspaceService(OmpProperties props, UserRepository users) {
        this.props = props;
        this.users = users;
        try {
            Files.createDirectories(props.workspacesRoot());
            Files.createDirectories(props.agentRoot());
        } catch (IOException e) {
            throw new RuntimeException("创建 omp 根目录失败", e);
        }
    }

    /**
     * Resolve a userId to its username, validating the username for path safety.
     * This prevents directory traversal even if the database contains malformed usernames.
     */
    private String resolveUsername(Long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));
        String username = user.getUsername();
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户未设置用户名: " + userId);
        }
        if (!USERNAME_PATH_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException("用户名包含非法字符: " + username);
        }
        return username;
    }

    /**
     * Validate repoId format before using it in path construction.
     * Allows alphanumeric and hyphen only, max 32 characters.
     */
    private void validateRepoId(String repoId) {
        if (repoId == null || repoId.isBlank()) {
            throw new IllegalArgumentException("repoId required");
        }
        if (repoId.length() > 32) {
            throw new IllegalArgumentException("repoId exceeds 32 characters");
        }
        if (!PATH_ID_PATTERN.matcher(repoId).matches()) {
            throw new IllegalArgumentException("repoId contains invalid characters: " + repoId);
        }
    }

    public Path userWorkspace(Long userId, String repoId) {
        String username = resolveUsername(userId);
        validateRepoId(repoId);
        Path p = props.workspacesRoot().resolve(username).resolve(repoId);
        try {
            Files.createDirectories(p);
            initGitIfNeeded(p);
            seedModelsConfig(userAgentDir(userId));
        } catch (IOException e) {
            throw new RuntimeException("准备 workspace 失败: " + p, e);
        }
        return p;
    }

    public Path userAgentDir(Long userId) {
        String username = resolveUsername(userId);
        Path p = props.agentRoot().resolve(username);
        try {
            Files.createDirectories(p);
            seedModelsConfig(p);
        } catch (IOException e) {
            throw new RuntimeException("准备 agent 目录失败: " + p, e);
        }
        return p;
    }

    /**
     * Seed models.yml on first creation so omp picks up our custom providers (DeepSeek).
     * omp reads models.yml from the agentDir; if absent, only built-in providers are available.
     * If user later edits the file, we leave it alone.
     */
    private void seedModelsConfig(Path agentDir) throws IOException {
        Path dest = agentDir.resolve("models.yml");
        if (Files.exists(dest)) return;
        try (var in = getClass().getClassLoader().getResourceAsStream("models.yml")) {
            if (in == null) return;
            Files.copy(in, dest);
            log.info("Seeded models.yml into {}", agentDir);
        }
    }

    private void initGitIfNeeded(Path dir) throws IOException {
        // 检查 .git 目录是否存在且有至少一个 commit
        // 空仓库（有 .git 但无 commit）也需要初始化
        boolean hasCommits = false;
        if (Files.exists(dir.resolve(".git"))) {
            try {
                runGitCapture(dir, "rev-parse", "HEAD");
                hasCommits = true;
            } catch (Exception ignored) {
                log.warn("Git repo at {} has no commits, will initialize", dir);
            }
        }
        if (hasCommits) return;
        // git init -b main requires Git 2.28+; do init then rename branch for compatibility.
        runGit(dir, "init");
        runGit(dir, "branch", "-M", "main");
        runGit(dir, "config", "user.email", GIT_AUTHOR_EMAIL);
        runGit(dir, "config", "user.name", GIT_AUTHOR_NAME);
        runGit(dir, "config", "commit.gpgsign", "false");
        Files.writeString(dir.resolve(".gitkeep"), "");
        runGit(dir, "add", ".gitkeep");
        runGit(dir, "commit", "-m", "init");
        log.info("Initialized git repo at {}", dir);
    }

    public List<String> listFiles(Long userId, String repoId) throws IOException {
        Path root = userWorkspace(userId, repoId);
        List<String> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !p.startsWith(root.resolve(".git")))
                .forEach(p -> out.add(root.relativize(p).toString()));
        }
        return out;
    }

    /**
     * 计算用户所有仓库的磁盘占用总量（单位 MB，向上取整）。
     * 遍历 {workspacesRoot}/{username}/ 下所有文件，排除各仓库的 .git 目录。
     * 用户工作区目录不存在时返回 0。
     */
    public long calculateDiskUsage(Long userId) {
        String username = resolveUsername(userId);
        Path userRoot = props.workspacesRoot().resolve(username);
        if (!Files.exists(userRoot)) return 0;
        long[] totalBytes = {0};
        try (Stream<Path> walk = Files.walk(userRoot)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> {
                    // 排除任意层级的 .git 目录内文件
                    for (Path seg : userRoot.relativize(p)) {
                        if (".git".equals(seg.toString())) return false;
                    }
                    return true;
                })
                .forEach(p -> {
                    try {
                        totalBytes[0] += Files.size(p);
                    } catch (IOException ignored) {
                        // 文件可能在遍历期间被删除，忽略
                    }
                });
        } catch (IOException e) {
            log.warn("计算磁盘占用失败: user={} path={}", userId, userRoot, e);
            return 0;
        }
        // 字节 → MB，向上取整
        return (totalBytes[0] + (1024 * 1024 - 1)) / (1024 * 1024);
    }

    /**
     * Directories we always skip when walking a workspace tree. Mirrors a
     * permissive .gitignore so the UI doesn't drown in noise from build/cache
     * artefacts. Exposed package-private so the controller can echo it back.
     */
    public static final Set<String> IGNORED_DIRS = Set.of(
            ".git", "node_modules", "__pycache__", "dist", "target",
            ".venv", "venv", ".next", ".nuxt", ".cache", "build",
            ".idea", ".vscode", "coverage", ".pytest_cache", ".mypy_cache",
            ".tox", ".gradle", "out", ".parcel-cache", ".turbo"
    );

    /**
     * Recursive shape used by the workspace tree endpoint.
     */
    public record TreeNode(String name, String path, String type, List<TreeNode> children) {
        public static TreeNode dir(String name, String path, List<TreeNode> children) {
            return new TreeNode(name, path, "dir", children);
        }
        public static TreeNode file(String name, String path) {
            return new TreeNode(name, path, "file", List.of());
        }
    }

    /**
     * Build a tree view of the workspace up to {@code maxDepth} levels deep.
     * depth = 1 returns just the root entries; directories are returned with
     * their immediate children so the UI can render one-click expansion.
     */
    public List<TreeNode> listTree(Long userId, String repoId, int maxDepth) throws IOException {
        Path root = userWorkspace(userId, repoId);
        int capped = Math.max(1, Math.min(maxDepth, 8));
        List<TreeNode> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            List<Path> entries = new ArrayList<>();
            for (Path p : ds) entries.add(p);
            entries.sort((a, b) -> {
                // Directories first, then files; alphabetical within each group.
                boolean ad = Files.isDirectory(a), bd = Files.isDirectory(b);
                if (ad != bd) return ad ? -1 : 1;
                return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
            });
            for (Path p : entries) {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p) && IGNORED_DIRS.contains(name)) continue;
                String rel = root.relativize(p).toString();
                if (Files.isDirectory(p)) {
                    out.add(TreeNode.dir(name, rel, capped > 1 ? walkDir(p, root, 1, capped) : List.of()));
                } else {
                    out.add(TreeNode.file(name, rel));
                }
            }
        }
        return out;
    }

    private List<TreeNode> walkDir(Path dir, Path root, int depth, int maxDepth) throws IOException {
        List<TreeNode> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            List<Path> entries = new ArrayList<>();
            for (Path p : ds) entries.add(p);
            entries.sort((a, b) -> {
                boolean ad = Files.isDirectory(a), bd = Files.isDirectory(b);
                if (ad != bd) return ad ? -1 : 1;
                return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
            });
            for (Path p : entries) {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p) && IGNORED_DIRS.contains(name)) continue;
                String rel = root.relativize(p).toString();
                if (Files.isDirectory(p)) {
                    out.add(TreeNode.dir(name, rel,
                            depth + 1 < maxDepth ? walkDir(p, root, depth + 1, maxDepth) : List.of()));
                } else {
                    out.add(TreeNode.file(name, rel));
                }
            }
        }
        return out;
    }

    public String readFile(Long userId, String repoId, String relativePath) throws IOException {
        Path root = userWorkspace(userId, repoId);
        Path file = root.resolve(relativePath).normalize();
        if (!file.startsWith(root)) {
            throw new IOException("路径超出工作区范围");
        }
        return Files.readString(file);
    }

    public void writeFile(Long userId, String repoId, String relativePath, String content) throws IOException {
        Path root = userWorkspace(userId, repoId);
        Path file = root.resolve(relativePath).normalize();
        if (!file.startsWith(root)) {
            throw new IOException("路径超出工作区范围");
        }
        if (!Files.exists(file)) {
            throw new IOException("文件不存在: " + relativePath);
        }
        Files.writeString(file, content);

        // 同步写入 agent-root 根目录：编辑项目根目录的 mcp.json / APPEND_SYSTEM.md 后，
        // 自动将内容同步到 agentRoot/ 下，使全局 OMP 进程感知变更。
        String fileName = Path.of(relativePath).getFileName().toString();
        if ("mcp.json".equals(fileName) || "APPEND_SYSTEM.md".equals(fileName)) {
            Path agentFile = props.agentRoot().resolve(fileName);
            Files.createDirectories(agentFile.getParent());
            Files.writeString(agentFile, content);
            log.info("Synced {} to agent-root {}", fileName, agentFile);
        }
    }

    public String diff(Long userId, String repoId, String refA, String refB) {
        Path root = userWorkspace(userId, repoId);
        return runGitCapture(root, "diff", refA + ".." + refB);
    }

    public String log(Long userId, String repoId, int n) {
        Path root = userWorkspace(userId, repoId);
        return runGitCapture(root, "log", "--oneline", "-n", String.valueOf(n));
    }

    /**
     * Copy an entire workspace directory to a new repoId under the same user.
     * Copies all files including hidden files and subdirectories, excluding .git.
     * The target repo is git-initialized after copy.
     *
     * @param userId        the user who owns the repo
     * @param sourceRepoId  the repo to copy from
     * @param targetRepoId  the new repo identifier (must pass validation)
     * @return the path to the new workspace
     */
    public Path copyWorkspace(Long userId, String sourceRepoId, String targetRepoId) {
        validateRepoId(targetRepoId);
        String username = resolveUsername(userId);
        Path source = props.workspacesRoot().resolve(username).resolve(sourceRepoId);
        Path target = props.workspacesRoot().resolve(username).resolve(targetRepoId);

        if (!Files.exists(source)) {
            throw new IllegalArgumentException("源仓库不存在: " + sourceRepoId);
        }
        if (Files.exists(target)) {
            throw new IllegalArgumentException("目标仓库已存在: " + targetRepoId);
        }

        try {
            // Copy all files including hidden files, excluding .git directory
            Files.walk(source).forEach(src -> {
                Path rel = source.relativize(src);
                Path dst = target.resolve(rel);
                // Skip .git directory
                if (rel.startsWith(".git")) return;
                try {
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst);
                    } else {
                        Files.createDirectories(dst.getParent());
                        Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("复制失败: " + src, e);
                }
            });

            // Initialize git in the new repo
            initGitIfNeeded(target);
            log.info("Copied workspace {} -> {} for user {}", sourceRepoId, targetRepoId, userId);
            return target;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("复制工作区失败", e);
        }
    }

    /**
     * Delete a workspace directory and its database record.
     * This operation is irreversible.
     *
     * @param userId the user who owns the repo
     * @param repoId the repo to delete
     */
    public void deleteWorkspace(Long userId, String repoId) {
        String username = resolveUsername(userId);
        Path target = props.workspacesRoot().resolve(username).resolve(repoId);

        if (!Files.exists(target)) {
            throw new IllegalArgumentException("仓库不存在: " + repoId);
        }

        try {
            // Recursively delete the directory
            try (Stream<Path> walk = Files.walk(target)) {
                walk.sorted((a, b) -> -a.compareTo(b)) // Delete files before directories
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new RuntimeException("删除失败: " + p, e);
                        }
                    });
            }
            log.info("Deleted workspace {} for user {}", repoId, userId);
        } catch (IOException e) {
            throw new RuntimeException("删除工作区失败", e);
        }
    }

    // ========================================================================
    // 会话创建三策略：cloneRepository / initFromTemplate / copyResourceDir
    // ========================================================================

    /**
     * 通过 {@code git clone} 把远程仓库拉取到用户工作区。
     *
     * <p>不走 {@link #userWorkspace} 的 git init 副作用——clone 目标目录必须是空目录。
     * 当前仅支持匿名 / 公网 https：Controller 层做 scheme 白名单 + SSRF 防护，
     * 此处再追加 {@code --config credential.helper=} 禁用交互凭证避免挂死。
     *
     * @param userId  仓库归属用户
     * @param repoId  新仓库标识
     * @param url     远程仓库 URL（已通过 Controller 校验）
     * @param branch  可选分支
     * @param depth   可选 --depth 值
     * @return 新建的工作区目录
     * @throws IOException      git 进程失败或非零退出
     * @throws RuntimeException 超时（>180s）或目标目录已存在
     */
    public Path cloneRepository(Long userId, String repoId, String url, String branch, Integer depth) {
        validateRepoId(repoId);
        String username = resolveUsername(userId);
        Path parent = props.workspacesRoot().resolve(username);
        Path target = parent.resolve(repoId);
        if (Files.exists(target)) {
            throw new IllegalArgumentException("目标目录已存在: " + target);
        }
        try {
            Files.createDirectories(parent);

            List<String> args = new ArrayList<>();
            args.add("git");
            args.add("clone");
            if (depth != null && depth > 0) {
                args.add("--depth");
                args.add(String.valueOf(depth));
            }
            if (branch != null && !branch.isBlank()) {
                args.add("--branch");
                args.add(branch);
            }
            args.add("--single-branch");
            // 禁用交互凭证：私有仓库直接失败而不是挂死等待 stdin
            args.add("--config");
            args.add("credential.helper=");
            args.add(url);
            args.add(target.toString());

            log.info("[clone] user={} repo={} url={} branch={} depth={}", userId, repoId, url, branch, depth);
            ProcessBuilder pb = new ProcessBuilder(args)
                    .directory(parent.toFile())
                    .redirectErrorStream(true);
            Process p = pb.start();
            byte[] out;
            boolean done;
            try {
                out = p.getInputStream().readAllBytes();
                done = p.waitFor(180, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
                throw new RuntimeException("克隆被中断", e);
            }
            if (!done) {
                p.destroyForcibly();
                throw new RuntimeException("克隆超时（>180s）: " + url);
            }
            int code = p.exitValue();
            String stderr = new String(out, StandardCharsets.UTF_8);
            if (code != 0) {
                throw new IOException("克隆失败 (exit=" + code + "): " + stderr.trim());
            }

            // 把 HEAD 重命名为 main（保持与 initGitIfNeeded 一致）
            try {
                runGit(target, "branch", "-M", "main");
            } catch (Exception e) {
                // remote HEAD 可能已叫 main，重命名失败不影响主流程
                log.debug("[clone] branch -M main skipped: {}", e.getMessage());
            }
            log.info("[clone] success user={} repo={} path={}", userId, repoId, target);
            return target;
        } catch (IOException e) {
            // 失败时尽量清掉半成品目录
            try {
                if (Files.exists(target)) {
                    deleteWorkspace(userId, repoId);
                }
            } catch (Exception ignored) {
                // 清理失败不掩盖原始错误
            }
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * 把 classpath 下的内置模板拷贝到用户工作区，然后 git init 把模板作为首次 commit。
     *
     * <p>模板固定为 {@code templates/frontend} 或 {@code templates/backend}，
     * 由 Controller 层做枚举校验，此处二次防御。
     *
     * @param userId   仓库归属用户
     * @param repoId   新仓库标识
     * @param template "frontend" 或 "backend"
     * @return 新建的工作区目录
     */
    public Path initFromTemplate(Long userId, String repoId, String template) {
        validateRepoId(repoId);
        String base = switch (template) {
            case "frontend" -> "templates/frontend";
            case "backend" -> "templates/backend";
            default -> throw new IllegalArgumentException("不支持的模板: " + template);
        };

        String username = resolveUsername(userId);
        Path parent = props.workspacesRoot().resolve(username);
        Path target = parent.resolve(repoId);
        if (Files.exists(target)) {
            throw new IllegalArgumentException("目标目录已存在: " + target);
        }
        try {
            Files.createDirectories(parent);
            Files.createDirectories(target);
            copyResourceDir(base, target);
            // 模板自带 README.md / package.json / pom.xml，作为首次 commit 内容
            initGitIfNeeded(target);
            log.info("[init-template] user={} repo={} template={} path={}", userId, repoId, template, target);
            return target;
        } catch (IOException e) {
            try {
                if (Files.exists(target)) {
                    deleteWorkspace(userId, repoId);
                }
            } catch (Exception ignored) {
            }
            throw new RuntimeException("初始化模板失败: " + e.getMessage(), e);
        }
    }

    /**
     * 把 classpath 下的目录树拷贝到目标路径。
     *
     * <p>使用 {@link CodeSource} 限定根（dev 期是 {@code target/classes}，jar 期是 jar 内），
     * 避免 {@code getResourceAsStream("/" + base)} 类写法被注入 {@code ../../}。
     * 遍历时校验每条 entry 的相对路径必须以 {@code classpathBase + "/"} 开头。
     *
     * <p>模板目录跳过 {@code .git} / {@code target} / {@code node_modules} 等构建产物。
     */
    private void copyResourceDir(String classpathBase, Path target) throws IOException {
        CodeSource src = WorkspaceService.class.getProtectionDomain().getCodeSource();
        if (src == null || src.getLocation() == null) {
            throw new IOException("无法定位 classpath 根目录");
        }
        Path root;
        try {
            root = Path.of(src.getLocation().toURI());
        } catch (Exception e) {
            throw new IOException("无法解析 classpath 根目录 URI: " + e.getMessage(), e);
        }
        Path baseDir = root.resolve(classpathBase);
        if (!Files.exists(baseDir)) {
            throw new IOException("模板目录不存在: " + classpathBase);
        }
        List<Path> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(baseDir)) {
            walk.forEach(entries::add);
        }
        for (Path srcPath : entries) {
            Path rel = baseDir.relativize(srcPath);
            String relStr = rel.toString().replace('\\', '/');
            // 白名单校验：防止相对路径逃逸
            if (relStr.contains("..")) {
                throw new IOException("非法模板路径: " + relStr);
            }
            // 跳过常见构建产物目录（保留 .gitignore / .gitkeep 等点文件）
            boolean skip = false;
            for (Path seg : rel) {
                String name = seg.toString();
                if (Set.of(".git", "node_modules", "target", "dist", "build").contains(name)) {
                    skip = true;
                    break;
                }
            }
            if (skip) continue;

            Path dest = target.resolve(rel).normalize();
            if (!dest.startsWith(target)) {
                throw new IOException("模板路径越界: " + relStr);
            }
            if (Files.isDirectory(srcPath)) {
                Files.createDirectories(dest);
            } else {
                Files.createDirectories(dest.getParent());
                Files.copy(srcPath, dest, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    /**
     * Snapshot pending changes. Returns the new HEAD sha, or empty if nothing changed.
     * Called from AuditService after each successful tool execution.
     */
    public Optional<String> snapshot(Long userId, String repoId, String message) {
        Path root = userWorkspace(userId, repoId);
        try {
            runGit(root, "add", "-A");
            String status = runGitCapture(root, "status", "--porcelain");
            if (status.isBlank()) return Optional.empty();
            runGit(root, "commit", "-m", message);
            return Optional.of(runGitCapture(root, "rev-parse", "HEAD").trim());
        } catch (Exception e) {
            log.warn("Snapshot failed for {}/{}: {}", userId, repoId, e.getMessage());
            return Optional.empty();
        }
    }

    private void runGit(Path cwd, String... args) throws IOException {
        runGitCapture(cwd, args);
    }

    private String runGitCapture(Path cwd, String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            for (String a : args) cmd.add(a);
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            int code = p.waitFor();
            String s = new String(out, StandardCharsets.UTF_8);
            if (code != 0) {
                throw new IOException("git " + String.join(" ", args) + " failed: " + s);
            }
            return s;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("git command failed in " + cwd, e);
        }
    }
}