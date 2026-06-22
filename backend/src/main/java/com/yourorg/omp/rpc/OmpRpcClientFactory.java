package com.yourorg.omp.rpc;

import com.yourorg.omp.admin.AdminConfigService;
import com.yourorg.omp.config.OmpProperties;
import com.yourorg.omp.workspace.WorkspaceService;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
                api
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
}