package com.yourorg.omp.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourorg.omp.config.OmpProperties;
import com.yourorg.omp.entity.AdminConfig;
import com.yourorg.omp.entity.ModelConfigEntity;
import com.yourorg.omp.repo.AdminConfigRepository;
import com.yourorg.omp.repo.ModelConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hot-loaded admin configuration. Values fall back to {@link OmpProperties} defaults when not set.
 *
 * <p>Changes apply to <strong>newly spawned</strong> omp processes; running processes need an explicit
 * {@code POST /admin/sessions/{id}/reload} to pick up the new values.
 *
 * <p>Model configuration resolution order:
 * <ol>
 *   <li>{@code admin_config.model.active} — legacy hot-override key (highest priority)</li>
 *   <li>{@code model_config} table — active row determined by {@code active = true}</li>
 *   <li>{@link OmpProperties} — application.yml defaults (lowest priority)</li>
 * </ol>
 */
@Service
public class AdminConfigService {

    /** JSON shape: {"provider":"anthropic","modelId":"claude-sonnet-4-5","thinkingLevel":"medium"} */
    public static final String KEY_MODEL_ACTIVE = "model.active";
    /** JSON shape: ["read","edit","write","bash","grep","find"] */
    public static final String KEY_OMP_TOOLS = "omp.flags.tools";
    /** Plain string: "medium" */
    public static final String KEY_OMP_THINKING = "omp.flags.thinking";
    /** Plain string: "always-ask" | "write" | "yolo" */
    public static final String KEY_OMP_APPROVAL_MODE = "omp.flags.approval-mode";
    /** Plain string: API key passed to omp via --api-key. If unset, OMP_API_KEY env wins. */
    public static final String KEY_VAULT_API_KEY = "vault.apiKey";

    private final AdminConfigRepository repo;
    private final ModelConfigRepository modelConfigRepo;
    private final OmpProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public AdminConfigService(AdminConfigRepository repo, ModelConfigRepository modelConfigRepo, OmpProperties props) {
        this.repo = repo;
        this.modelConfigRepo = modelConfigRepo;
        this.props = props;
    }

    public Map<String, JsonNode> getAll() {
        Map<String, JsonNode> out = new HashMap<>();
        for (AdminConfig c : repo.findAll()) {
            try {
                out.put(c.getConfigKey(), mapper.readTree(c.getConfigValue()));
            } catch (JsonProcessingException e) {
                out.put(c.getConfigKey(), mapper.createObjectNode().put("raw", c.getConfigValue()));
            }
        }
        return out;
    }

    @Transactional
    public void set(String key, Object value, String description, String updatedBy) {
        AdminConfig c = repo.findById(key).orElseGet(AdminConfig::new);
        c.setConfigKey(key);
        try {
            c.setConfigValue(mapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Config value not serializable", e);
        }
        c.setDescription(description);
        c.setUpdatedAt(Instant.now());
        c.setUpdatedBy(updatedBy);
        repo.save(c);
    }

    @Transactional
    public void delete(String key) {
        repo.deleteById(key);
    }

    public Optional<JsonNode> getJson(String key) {
        return repo.findById(key).flatMap(c -> {
            try { return Optional.of(mapper.readTree(c.getConfigValue())); }
            catch (JsonProcessingException e) { return Optional.empty(); }
        });
    }

    public Optional<String> apiKey() {
        // 1. vault.apiKey 显式配置（已弃用，但保持向后兼容）
        // 2. model.active.apiKey（主数据源 — admin_config）
        // 3. model_config.active.apiKey（新表）
        // 4. application.yml vault.api-key（最后回退）

        // Step 1+2: model.active.apiKey
        Optional<String> fromModel = modelConfig().map(ModelConfig::apiKey).filter(s -> s != null && !s.isBlank());
        if (fromModel.isPresent()) return fromModel;

        // Step 3: model_config.active.apiKey（如果 modelConfig() 已经读了新表，这里不会重复）
        // modelConfig() 已包含新表回退，所以 fromModel 已经覆盖了 model_config.active

        Optional<String> fromDb = repo.findById(KEY_VAULT_API_KEY).flatMap(c -> {
            try {
                JsonNode n = mapper.readTree(c.getConfigValue());
                if (n.isTextual()) return Optional.of(n.asText());
                return Optional.of(n.toString());
            } catch (JsonProcessingException e) {
                return Optional.ofNullable(c.getConfigValue());
            }
        }).filter(s -> !s.isBlank());
        return fromDb.or(() -> Optional.ofNullable(props.vault().apiKey()).filter(s -> !s.isBlank()));
    }

    /** Read full model.active node — provider/modelId/baseUrl. Falls back to model_config table. */
    public java.util.Optional<ModelConfig> modelConfig() {
        // 1. 优先从 admin_config 中的 model.active 读取（热覆盖，向后兼容）
        Optional<ModelConfig> fromAdmin = getJson(KEY_MODEL_ACTIVE).map(node -> {
            String provider = node.path("provider").asText(null);
            String modelId = node.path("modelId").asText(null);
            String baseUrl = node.path("baseUrl").asText(null);
            String api = node.path("api").asText(null);
            String apiKey = node.path("apiKey").asText(null);
            return new ModelConfig(provider, modelId, baseUrl, api, apiKey);
        });
        if (fromAdmin.isPresent()) return fromAdmin;

        // 2. 从新表 model_config 读取活跃配置
        Optional<ModelConfigEntity> active = modelConfigRepo.findByActiveTrue();
        return active.map(e -> new ModelConfig(e.getProvider(), e.getModelId(),
                e.getBaseUrl(), e.getApi(), e.getApiKey()));
    }

    public record ModelConfig(String provider, String modelId, String baseUrl, String api, String apiKey) {}

    public Optional<List<String>> toolWhitelist() {
        return getJson(KEY_OMP_TOOLS).map(node -> {
            List<String> out = new java.util.ArrayList<>();
            if (node.isArray()) node.forEach(n -> out.add(n.asText()));
            return out;
        });
    }

    public Optional<String> thinkingLevel() {
        return getJson(KEY_OMP_THINKING).map(JsonNode::asText);
    }

    public Optional<String> approvalMode() {
        return getJson(KEY_OMP_APPROVAL_MODE).map(JsonNode::asText);
    }
}