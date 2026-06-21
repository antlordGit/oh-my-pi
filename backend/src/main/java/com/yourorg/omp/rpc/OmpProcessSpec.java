package com.yourorg.omp.rpc;

import com.yourorg.omp.config.OmpProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable spec describing how to spawn a single omp child process.
 * Built by {@link OmpRpcClientFactory}; consumed by {@link OmpRpcClient}.
 *
 * <p>Args are derived from {@link OmpProperties} defaults + optional admin overrides
 * + per-session overrides (e.g. resume path).
 */
public record OmpProcessSpec(
        String ompBinary,
        Path workspaceCwd,
        Path agentDir,
        Path stderrLog,
        Map<String, String> env,
        List<String> extraArgs,
        String resumeSessionPath,
        String forkFromSessionPath,
        String apiKey,
        String thinkingLevel,
        String approvalMode,
        List<String> tools,
        boolean noTui,
        String provider,
        String modelId,
        String baseUrl,
        String api  // e.g. "anthropic-messages", "openai-completions"
) {
    public OmpProcessSpec {
        env = env == null ? Map.of() : Map.copyOf(env);
        extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
        tools = tools == null ? List.of() : List.copyOf(tools);
        Objects.requireNonNull(ompBinary, "ompBinary");
        Objects.requireNonNull(workspaceCwd, "workspaceCwd");
        Objects.requireNonNull(agentDir, "agentDir");
        Objects.requireNonNull(stderrLog, "stderrLog");
    }

    /**
     * Build the argv array for ProcessBuilder. Order matches docs/rpc.md "Startup":
     *   omp --mode rpc-ui [flags] [--resume path | --fork path]
     *
     * Use rpc-ui (not plain rpc) so the `ask` tool is registered and can emit
     * extension_ui_request frames for interactive user prompts.
     */
    public List<String> toArgv() {
        List<String> argv = new ArrayList<>();
        argv.add(ompBinary);
        argv.add("--mode");
        argv.add("rpc-ui");
        if (thinkingLevel != null && !thinkingLevel.isBlank()) {
            argv.add("--thinking");
            argv.add(thinkingLevel);
        }
        if (approvalMode != null && !approvalMode.isBlank()) {
            argv.add("--approval-mode");
            argv.add(approvalMode);
        }
        if (!tools.isEmpty()) {
            argv.add("--tools");
            argv.add(String.join(",", tools));
        }
        // Don't auto-load CLAUDE.md / AGENTS.md from upper directories — those belong to
        // the operator (host), not to the user's omp session inside the workspace.
        argv.add("--no-rules");
        argv.add("--no-skills");
        // --provider is the provider id from models.yml (e.g. "deepseek"); fallback to "openai" when not set.
        argv.add("--provider");
        argv.add(provider == null || provider.isBlank() ? "openai" : provider);
        if (modelId != null && !modelId.isBlank()) {
            argv.add("--model");
            argv.add(modelId);
        }
        if (apiKey != null && !apiKey.isBlank()) {
            argv.add("--api-key");
            argv.add(apiKey);
        }
        if (resumeSessionPath != null && !resumeSessionPath.isBlank()) {
            argv.add("--resume");
            argv.add(resumeSessionPath);
        }
        if (forkFromSessionPath != null && !forkFromSessionPath.isBlank()) {
            argv.add("--fork");
            argv.add(forkFromSessionPath);
        }
        argv.addAll(extraArgs);
        return argv;
    }

    /**
     * Env vars to merge into the child process. Always overrides:
     *   PI_CODING_AGENT_DIR → agentDir.toString()
     */
    public Map<String, String> effectiveEnv() {
        Map<String, String> m = new HashMap<>(env);
        m.put("PI_CODING_AGENT_DIR", agentDir.toAbsolutePath().toString());
        m.putIfAbsent("PI_NOTIFICATIONS", "off");

        // Set base URL env var according to the API protocol so the OMP process
        // resolves the correct backend host (e.g. an Anthropic-compatible proxy
        // backed by xunfei needs its baseUrl via ANTHROPIC_BASE_URL, not
        // OPENAI_BASE_URL).
        if (baseUrl != null && !baseUrl.isBlank()) {
            if ("anthropic-messages".equals(api)) {
                m.put("ANTHROPIC_BASE_URL", baseUrl);
            } else {
                m.put("OPENAI_BASE_URL", baseUrl);
            }
        }

        return m;
    }
}