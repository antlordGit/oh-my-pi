-- ===========================================================================
-- V4: 模型配置管理 — 支持多模型配置，可激活其中一个
-- ===========================================================================

-- 1. 创建模型配置表
CREATE TABLE model_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_name VARCHAR(128) NOT NULL COMMENT '配置名称，用于人类识别',
    provider VARCHAR(64) NOT NULL,
    model_id VARCHAR(128) NOT NULL,
    base_url VARCHAR(512) NULL,
    api VARCHAR(64) NULL,
    api_key VARCHAR(512) NULL COMMENT 'API Key',
    config_json JSON NULL COMMENT '完整配置JSON（含 discovery/modelOverrides 等）',
    active BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否当前激活',
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_config_name (config_name),
    INDEX idx_active (active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. 如果 admin_config 中存在 model.active，迁移到新表并激活
-- 注意：使用存储过程处理"key 不存在"的情况，避免 Flyway 迁移失败
INSERT INTO model_config (config_name, provider, model_id, base_url, api, api_key, config_json, active, sort_order)
SELECT
    JSON_UNQUOTE(JSON_EXTRACT(config_value, '$.provider')),
    JSON_UNQUOTE(JSON_EXTRACT(config_value, '$.provider')),
    COALESCE(JSON_UNQUOTE(JSON_EXTRACT(config_value, '$.modelId')), 'unknown'),
    JSON_UNQUOTE(JSON_EXTRACT(config_value, '$.baseUrl')),
    JSON_UNQUOTE(JSON_EXTRACT(config_value, '$.api')),
    JSON_UNQUOTE(JSON_EXTRACT(config_value, '$.apiKey')),
    config_value,
    TRUE,
    0
FROM admin_config
WHERE config_key = 'model.active'
  AND JSON_EXTRACT(config_value, '$.provider') IS NOT NULL;
