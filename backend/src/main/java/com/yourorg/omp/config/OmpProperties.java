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
        Vault vault
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
}