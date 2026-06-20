package com.yourorg.omp.workspace;

import com.yourorg.omp.config.OmpProperties;
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
import java.util.stream.Stream;

/**
 * Owns on-disk layout for user code and per-user omp state.
 *
 * <pre>
 *   {workspacesRoot}/{userId}/{repoId}/         ← user code (git-initialized)
 *   {agentRoot}/{userId}/                       ← PI_CODING_AGENT_DIR
 *   {agentRoot}/{userId}/sessions/...           ← omp's JSONL session files
 * </pre>
 *
 * <p>Repo directories are auto-created and git-initialized on first access. Git is the source of truth
 * for file-level history; session JSONL is the source of truth for conversation history.
 */
@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);
    private static final String GIT_AUTHOR_NAME = "omp";
    private static final String GIT_AUTHOR_EMAIL = "omp@system.local";

    private final OmpProperties props;

    public WorkspaceService(OmpProperties props) {
        this.props = props;
        try {
            Files.createDirectories(props.workspacesRoot());
            Files.createDirectories(props.agentRoot());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create omp root directories", e);
        }
    }

    public Path userWorkspace(Long userId, String repoId) {
        Path p = props.workspacesRoot().resolve(String.valueOf(userId)).resolve(repoId);
        try {
            Files.createDirectories(p);
            initGitIfNeeded(p);
            seedModelsConfig(userAgentDir(userId));
        } catch (IOException e) {
            throw new RuntimeException("Failed to prepare workspace " + p, e);
        }
        return p;
    }

    public Path userAgentDir(Long userId) {
        Path p = props.agentRoot().resolve(String.valueOf(userId));
        try {
            Files.createDirectories(p);
            seedModelsConfig(p);
        } catch (IOException e) {
            throw new RuntimeException("Failed to prepare agent dir " + p, e);
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
            throw new IOException("Path escapes workspace");
        }
        return Files.readString(file);
    }

    public void writeFile(Long userId, String repoId, String relativePath, String content) throws IOException {
        Path root = userWorkspace(userId, repoId);
        Path file = root.resolve(relativePath).normalize();
        if (!file.startsWith(root)) {
            throw new IOException("Path escapes workspace");
        }
        if (!Files.exists(file)) {
            throw new IOException("File does not exist: " + relativePath);
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