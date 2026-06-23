package com.yourorg.omp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

/**
 * Application properties under the `app.omp` prefix. Bound by Spring at startup.
 */
@ConfigurationProperties(prefix = "app.omp")
public record OmpProperties(
        String binary,
        Path workspacesRoot,
        Path agentRoot,
        Path stderrLogDir,
        Pool pool,
        int perUserSessionLimit,
        DefaultFlags defaultFlags,
        DefaultModel defaultModel,
        Security security,
        Vault vault,
        Ide ide,
        // 共享 agent 根目录（容器内 /data/omp/agent 持久化卷）。
        // entrypoint 在 root 用户的 ~/.omp/agent 上建 symlink 指向这里，
        // Java spawn 时为每个用户在该目录下建 extensions/skills/hooks/tools 的子 symlink。
        // 共享目录 = 运维统一安装插件/skill/MCP/hook 的入口。
        Path sharedAgentRoot,
        // 是否为每个 omp 子进程加载 skills。默认 true。
        // Java spawn 透传给 OmpProcessSpec，未启用时不传 --no-skills 跳过。
        Boolean enableSkills,
        // 是否加载 rules（CLAUDE.md / AGENTS.md）。默认 false，安全考虑。
        // 启用时移除 --no-rules，便于运维注入组织级 rules。
        Boolean enableRules
) {
    public record Pool(int maxConcurrent, int idleTtlMinutes) {}

    public record DefaultFlags(
            String tools,
            String thinking,
            String approvalMode,
            boolean noTui
    ) {
        public List<String> toolList() {
            if (tools == null || tools.isBlank()) return List.of();
            return List.of(tools.split(","));
        }
    }

    public record DefaultModel(String provider, String modelId) {}

    public record Security(String jwtSecret, int jwtTtlHours, BootstrapAdmin bootstrapAdmin) {}

    public record BootstrapAdmin(String username, String password) {}

    public record Vault(String apiKey) {}

    /**
     * IDE (openvscode-server) integration properties.
     *
     * @param enabled  是否启用 IDE 功能
     * @param publicBaseUrl 浏览器可访问的 openvscode-server 地址（如 http://192.168.1.100:3000）
     * @param signingKey 64 个 hex 字符 (32 字节) 的 HMAC-SHA256 key。
     *                   非空时，IdeService 为每个 ?folder= URL 附加 &sig= 签名；
     *                   code-server 端持同一 key 验签，篡改 folder 的请求被 403 拒绝。
     *                   空时退化为无签名 plain URL。
     */
    public record Ide(boolean enabled, String publicBaseUrl, String signingKey) {}
}