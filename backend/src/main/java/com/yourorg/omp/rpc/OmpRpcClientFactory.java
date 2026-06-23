package com.yourorg.omp.rpc;

import com.yourorg.omp.admin.AdminConfigService;
import com.yourorg.omp.config.OmpProperties;
import com.yourorg.omp.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds {@link OmpProcessSpec} instances and spawns {@link OmpRpcClient}s.
 *
 * <p>Resolution order for runtime config:
 * <ol>
 *   <li>Admin overrides ({@link AdminConfigService}) — applied at startup</li>
 *   <li>Default flags from {@link OmpProperties}</li>
 * </ol>
 *
 * <p>Before spawning OMP, the active model from {@code model.active} is
 * written to the user's {@code models.yml} so OMP can find it.
 */
@Component
public class OmpRpcClientFactory {

    private static final Logger log = LoggerFactory.getLogger(OmpRpcClientFactory.class);

    /** CodeGraph 索引每会话至少触发一次刷新的最小间隔（mtime 比对窗口）。 */
    private static final Duration CODEGRAPH_INDEX_THROTTLE = Duration.ofSeconds(30);

    private final OmpProperties props;
    private final AdminConfigService adminConfig;
    private final WorkspaceService workspaceService;

    public OmpRpcClientFactory(OmpProperties props,
                               AdminConfigService adminConfig,
                               WorkspaceService workspaceService) {
        this.props = props;
        this.adminConfig = adminConfig;
        this.workspaceService = workspaceService;
    }

    public OmpRpcClient spawn(String sessionId, Long userId, String repoId, String resumePath) throws IOException {
        Path workspace = workspaceService.userWorkspace(userId, repoId);
        Path agentDir = workspaceService.userAgentDir(userId);
        Path stderrLog = props.stderrLogDir().resolve("omp-" + sessionId + ".err.log");

        List<String> tools = adminConfig.toolWhitelist()
                .orElse(props.defaultFlags().toolList());
        String thinking = adminConfig.thinkingLevel().orElse(props.defaultFlags().thinking());
        String approvalMode = adminConfig.approvalMode().orElse(props.defaultFlags().approvalMode());
        String apiKey = adminConfig.apiKey().orElseGet(() -> props.vault().apiKey());

        // Resolve model: admin model.active wins; else default-model from properties.
        String provider = props.defaultModel().provider();
        String modelId = props.defaultModel().modelId();
        String baseUrl = null;
        String api = null;  // e.g. "anthropic-messages", "openai-completions"
        var mc = adminConfig.modelConfig();
        if (mc.isPresent()) {
            if (mc.get().provider() != null) provider = mc.get().provider();
            if (mc.get().modelId() != null) modelId = mc.get().modelId();
            if (mc.get().baseUrl() != null) baseUrl = mc.get().baseUrl();
            if (mc.get().api() != null) api = mc.get().api();
        }

        // 同步写入 models.yml，确保 OMP 能找到自定义模型
        syncModelsYml(agentDir, provider, modelId, baseUrl, api, apiKey);

        // MCP 配置 + system prompt 模板。
        // omp 启动时 PI_CODING_AGENT_DIR=agentDir，会自动发现：
        //   - agentDir/mcp.json    → MCP servers（含 codegraph stdio server）
        //   - agentDir/APPEND_SYSTEM.md → 追加到每次会话的 system prompt
        //
        // mcp.json：统一维护在 agentRoot/mcp.json 共享（运维或 entrypoint 落盘），
        //   每个用户 agentDir 下建一个 symlink 指向 ../mcp.json。一次维护，全局生效。
        //   用户级私有 MCP 不支持（symlink 每次 spawn 重建会清掉用户改动）。
        // APPEND_SYSTEM.md：全局模板 + 用户私有 (.user.md) 合并生成；
        //   用户私有放在 <agentDir>/APPEND_SYSTEM.user.md，由运维或用户手工创建。
        // 模板/共享文件缺失时静默跳过，开发机/容器内 omp 进程都能照常启动
        //   （只是没全局 MCP 或 prompt 注入）。
        ensureGlobalMcpSymlink(props.agentRoot(), agentDir);
        // 共享 agent 目录 symlinks：extensions/skills/hooks/tools。
        // 指向 /data/omp/agent/{name}/ → 一份运维安装的扩展/技能/钩子/工具，
        // 每个用户通过 PI_CODING_AGENT_DIR 隔离的子目录自动 symlink 共享。
        // 仅当 props.sharedAgentRoot() 配置且对应子目录存在时才建；
        // 任意 IO 异常静默跳过（与 mcp.json 对齐的容错策略）。
        if (props.sharedAgentRoot() != null) {
            ensureSharedSymlinks(props.sharedAgentRoot(), agentDir);
        }
        syncAppendSystemMd(
                props.appendSystemTemplatePath(),
                agentDir.resolve("APPEND_SYSTEM.user.md"),
                agentDir.resolve("APPEND_SYSTEM.md")
        );

        // 确保工作区 CodeGraph 索引最新（异步 daemon 线程，不阻塞 omp spawn）。
        // 兜底场景：watcher 在容器重启后失联期间漏掉的 git pull / IDE 外部改动。
        ensureCodeGraphIndexed(workspace);

        OmpProcessSpec spec = new OmpProcessSpec(
                props.binary(),
                workspace,
                agentDir,
                stderrLog,
                null,
                List.of(),
                resumePath,
                null,
                apiKey,
                thinking,
                approvalMode,
                tools,
                props.defaultFlags().noTui(),
                provider,
                modelId,
                baseUrl,
                api,
                props.enableSkills() == null || props.enableSkills(),
                props.enableRules() != null && props.enableRules()
        );
        return OmpRpcClient.start(sessionId, spec, Duration.ofSeconds(30));
    }

    /**
     * 将 model.active 动态同步到用户 models.yml，使 OMP 进程能找到自定义模型。
     * 只做增量合并：如果同名 provider 已存在且有相同 model id，跳过。
     * 其他已有内容保持不动。
     */
    @SuppressWarnings("unchecked")
    private void syncModelsYml(Path agentDir, String provider, String modelId,
                                String baseUrl, String api, String apiKey) {
        if (provider == null || provider.isBlank() || modelId == null || modelId.isBlank()) return;
        // 写到全局 agentRoot 下的 models.yml，所有用户共享
        Path dest = agentDir.resolve("models.yml");
        Yaml yaml = buildYaml();

        // 读取已有配置
        Map<String, Object> root;
        try {
            if (Files.exists(dest)) {
                String existing = Files.readString(dest);
                if (!existing.isBlank()) {
                    root = yaml.load(existing);
                } else {
                    root = new LinkedHashMap<>();
                }
            } else {
                root = new LinkedHashMap<>();
            }
        } catch (IOException e) {
            root = new LinkedHashMap<>();
        }
        if (root == null) root = new LinkedHashMap<>();

        Map<String, Object> providers = (Map<String, Object>) root.computeIfAbsent("providers", k -> new LinkedHashMap<>());
        Map<String, Object> providerCfg = (Map<String, Object>) providers.computeIfAbsent(provider, k -> new LinkedHashMap<>());

        // 用数据库激活配置的真实值覆盖 provider 级连接字段，让 models.yml 始终反映数据库真相。
        // 这是 OMP 进程启动时 ModelRegistry 一次性加载 apiKey 的唯一可靠注入点：
        // 进程一旦启动就把 apiKey 固化进 AuthStorage#configOverrides，运行中改文件不会重读。
        // 所以这里必须 put（覆盖），不能 putIfAbsent——否则 deepseek 这类首版被占位符污染过的
        // provider 永远换不到真实 key。其它字段（models 列表里的 thinking/compat 等丰富配置）保持不动。
        if (baseUrl != null && !baseUrl.isBlank()) providerCfg.put("baseUrl", baseUrl);
        if (api != null && !api.isBlank()) providerCfg.put("api", api);
        if (apiKey != null && !apiKey.isBlank()) providerCfg.put("apiKey", apiKey);

        // 增量添加 model
        List<Map<String, Object>> models = (List<Map<String, Object>>) providerCfg.computeIfAbsent("models", k -> new java.util.ArrayList<>());
        boolean found = models.stream().anyMatch(m -> modelId.equals(m.get("id")));
        if (!found) {
            Map<String, Object> newModel = new LinkedHashMap<>();
            newModel.put("id", modelId);
            newModel.put("name", modelId);
            models.add(newModel);
        }

        // 写回文件
        try {
            Files.createDirectories(agentDir);
            Files.writeString(dest, yaml.dump(root));
        } catch (IOException e) {
            // 写入失败不要阻塞启动——让 omp 自带报错兜底
        }
    }

    private static Yaml buildYaml() {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        opts.setIndent(2);
        return new Yaml(opts);
    }

    /**
     * 确保用户 agentDir 下有指向共享 mcp.json 的 symlink。
     * 静默兜底：
     * 1. agentRoot/mcp.json 不存在 → 跳过（开发机未放共享配置时不阻塞 omp 启动）
     * 2. 目录/symlink 操作失败 → 不抛，避免拖垮 spawn 主流程（omp 退化为不带 MCP 的工具集）
     */
    private static void ensureGlobalMcpSymlink(Path agentRoot, Path agentDir) {
        Path sharedMcp = agentRoot.resolve("mcp.json");
        if (!Files.isRegularFile(sharedMcp)) return;

        try {
            Files.createDirectories(agentDir);
            Path userMcp = agentDir.resolve("mcp.json");

            // 如果已经是正确 symlink，不用重建
            if (Files.isSymbolicLink(userMcp)) {
                Path target = Files.readSymbolicLink(userMcp);
                if (Path.of("../mcp.json").equals(target)) return;
            }

            // 旧文件/旧链接 → 删除重建
            Files.deleteIfExists(userMcp);

            // 创建相对路径 symlink: 从 userMcp 的父目录(agentDir) 指向 ../mcp.json
            Files.createSymbolicLink(userMcp, Path.of("../mcp.json"));
        } catch (IOException ignore) {
            // 任何 IO 异常都跳过（Windows 不支持 symlink/权限不够/...）
        }
    }

    /**
     * 为用户 agentDir 建立指向共享目录的 symlinks。
     *
     * <p>让每个用户的 omp 子进程（PI_CODING_AGENT_DIR=agentDir）自动发现
     * 运维统一安装在 {@code sharedAgentRoot} 下的扩展/技能/钩子/工具，
     * 同时保留 user 级别的 mcp.json / APPEND_SYSTEM.md / models.yml / sessions/ 隔离。
     *
     * <p>共享子目录清单：extensions / skills / hooks / tools。
     * 任意一个子目录在 sharedAgentRoot 下不存在 → 跳过（不创建空 symlink 误导 omp）。
     * symlink 目标指向 {@code ../../<subdir>}（从 userDir 到 sharedAgentRoot/<subdir>）：
     *   <pre>
     *   /data/omp/agent/                    ← sharedAgentRoot
     *   /data/omp/agent/extensions/foo.ts   ← 运维装的扩展
     *   /data/omp/agent/admin/              ← 用户 agentDir（PI_CODING_AGENT_DIR）
     *   /data/omp/agent/admin/extensions    → ../../extensions
     *   </pre>
     *
     * <p>静默兜底：任何 IO 异常都跳过（Windows / 权限 / 不存在等），
     * 与 {@link #ensureGlobalMcpSymlink} 同一容错策略。
     */
    private static void ensureSharedSymlinks(Path sharedAgentRoot, Path agentDir) {
        String[] sharedSubdirs = {"extensions", "skills", "hooks", "tools"};
        try {
            Files.createDirectories(agentDir);
            for (String subdir : sharedSubdirs) {
                Path shared = sharedAgentRoot.resolve(subdir);
                // 共享子目录不存在 → 不建 symlink（避免 omp 读空目录报错或加载失败）
                if (!Files.isDirectory(shared)) continue;

                Path userLink = agentDir.resolve(subdir);
                // sharedAgentRoot=/data/omp/agent, agentDir=/data/omp/agent/{user}，
                // {user} 是 {agent} 的子目录，相对路径就是 ../<subdir>。
                String target = "../" + subdir;

                // 已是正确 symlink → 不重建
                if (Files.isSymbolicLink(userLink)) {
                    Path existing = Files.readSymbolicLink(userLink);
                    if (Path.of(target).equals(existing)) continue;
                }

                // 旧文件/旧链接 → 删除重建
                Files.deleteIfExists(userLink);
                Files.createSymbolicLink(userLink, Path.of(target));
            }
        } catch (IOException ignore) {
            // 任意 IO 异常跳过：Windows 不支持 symlink、权限不够、磁盘满等
        }
    }

    /**
     * 合并全局 APPEND_SYSTEM 模板与用户私有覆盖到 omp 实际读取的 APPEND_SYSTEM.md。
     * 合并规则（按存在性矩阵）：
     *   - 仅全局：写入全局内容
     *   - 仅用户：写入用户内容
     *   - 都有：  全局 + "\n\n---\n\n" + 用户
     *   - 都无：  删除已生成的 target（保持 omp 无 append prompt 的纯净状态）
     * 异常静默兜底：读写失败时不阻塞 spawn，omp 退化为无 system prompt 追加。
     */
    private static void syncAppendSystemMd(Path templatePath, Path userOverride, Path target) {
        try {
            String global = readIfRegular(templatePath);
            String user = readIfRegular(userOverride);
            String merged;
            if (global != null && user != null) {
                merged = global.stripTrailing() + "\n\n---\n\n" + user.stripLeading();
            } else if (global != null) {
                merged = global;
            } else if (user != null) {
                merged = user;
            } else {
                // 两个源都不存在 → 清掉旧的生成文件，避免历史残留影响 omp
                Files.deleteIfExists(target);
                return;
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, merged, StandardCharsets.UTF_8);
        } catch (IOException ignore) {
            // 合并失败不阻塞 omp 启动
        }
    }

    /** 读 regular file 内容，文件不存在或非 regular 返回 null。IO 异常上抛。 */
    private static String readIfRegular(Path p) throws IOException {
        if (p == null || !Files.isRegularFile(p)) return null;
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /**
     * 异步确保工作区有最新的 CodeGraph 索引：
     * <ol>
     *   <li>若 {@code .codegraph/codegraph.db} mtime 距今 &lt; 30s → 跳过
     *       （同工作区频繁会话不重复劳动）</li>
     *   <li>若 {@code .codegraph/} 不存在 → 后台跑 {@code codegraph init -i}
     *       （init + 首次全量 index）</li>
     *   <li>若 {@code .codegraph/} 已存在 → 后台跑 {@code codegraph index --quiet}
     *       （全量重扫，兜底 watcher 失联场景）</li>
     * </ol>
     *
     * <p>codegraph 命令通过 PATH 解析（omp-allinone 镜像内 {@code /usr/local/bin/codegraph}；
     * 开发机若没装，{@code ProcessBuilder} 抛 IOException，log warn 不阻塞 omp 启动）。
     *
     * <p>后台执行：daemon 线程，omp spawn 不等待结果。codegraph 输出 DISCARD，
     * 不污染 backend log。MCP server 启动后通过 SQLite WAL 读最新数据，
     * 不需要 Java 端通知 omp。
     */
    private static void ensureCodeGraphIndexed(Path workspace) {
        Path dbFile = workspace.resolve(".codegraph").resolve("codegraph.db");
        boolean dbExists = Files.isRegularFile(dbFile);

        // throttle：mtime < 30s 跳过
        if (dbExists) {
            try {
                Instant dbMtime = Files.getLastModifiedTime(dbFile).toInstant();
                if (dbMtime.plus(CODEGRAPH_INDEX_THROTTLE).isAfter(Instant.now())) {
                    return;
                }
            } catch (IOException ignore) {
                // mtime 读不到 → 当作过期，继续 index
            }
        }

        String[] cmd = dbExists
                ? new String[]{"codegraph", "index", "--quiet", workspace.toString()}
                : new String[]{"codegraph", "init", "-i", workspace.toString()};

        Thread t = new Thread(() -> {
            try {
                Process p = new ProcessBuilder(cmd)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                int exit = p.waitFor();
                if (exit != 0) {
                    log.warn("[omp.codegraph] {} exit={} workspace={}", cmd[1], exit, workspace);
                } else {
                    log.info("[omp.codegraph] {} OK workspace={}", cmd[1], workspace);
                }
            } catch (IOException e) {
                // codegraph 不在 PATH（开发机本机无安装）/ 进程启动失败
                log.warn("[omp.codegraph] cannot spawn {}: {} (workspace={})", cmd[0], e.getMessage(), workspace);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "codegraph-index-" + workspace.getFileName());
        t.setDaemon(true);
        t.start();
    }
}