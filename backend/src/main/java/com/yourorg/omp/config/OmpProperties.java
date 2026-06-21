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
        Ide ide
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
     */
    public record Ide(boolean enabled, String publicBaseUrl) {}
}