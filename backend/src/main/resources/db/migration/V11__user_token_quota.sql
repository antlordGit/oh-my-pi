-- ===========================================================================
-- V11: 用户大模型 Token 额度（限额 + 已消耗累计，单位 Token，默认 0 表示不限）
-- ===========================================================================

ALTER TABLE users ADD COLUMN token_limit BIGINT NOT NULL DEFAULT 0 COMMENT 'Token 额度上限（0 表示不限）';
ALTER TABLE users ADD COLUMN token_used  BIGINT NOT NULL DEFAULT 0 COMMENT '已消耗 Token 累计';
