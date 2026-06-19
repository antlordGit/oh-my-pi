package com.yourorg.omp.rpc;

import com.yourorg.omp.admin.AdminConfigService;
import com.yourorg.omp.config.OmpProperties;
import com.yourorg.omp.workspace.WorkspaceService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Builds {@link OmpProcessSpec} instances and spawns {@link OmpRpcClient}s.
 *
 * <p>Resolution order for runtime config:
 * <ol>
 *   <li>Admin overrides ({@link AdminConfigService}) — applied at startup</li>
 *   <li>Default flags from {@link OmpProperties}</li>
 * </ol>
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
        var mc = adminConfig.modelConfig();
        if (mc.isPresent()) {
            if (mc.get().provider() != null) provider = mc.get().provider();
            if (mc.get().modelId() != null) modelId = mc.get().modelId();
            if (mc.get().baseUrl() != null) baseUrl = mc.get().baseUrl();
        }

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
                baseUrl
        );
        return OmpRpcClient.start(sessionId, spec, Duration.ofSeconds(30));
    }
}