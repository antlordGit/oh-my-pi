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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
        if (!PATH_ID_PATTERN.matcher(username).matches()) {
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
        if (Files.exists(dir.resolve(".git"))) return;
        runGit(dir, "init", "-b", "main");
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