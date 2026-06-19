-- ===========================================================================
-- V1: Initial schema for omp multi-tenant orchestrator
-- ===========================================================================

-- Users: simple username/password (passwords hashed with BCrypt at app layer).
CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'user',           -- 'user' | 'admin'
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_login_at TIMESTAMP(3) NULL
);

-- Repos: a workspace belongs to one user, addressed by repoId (free-form string).
CREATE TABLE repos (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    repo_id VARCHAR(128) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_user_repo (user_id, repo_id),
    CONSTRAINT fk_repos_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Sessions: metadata mirror of an omp session file.
-- `omp_session_file` is the absolute path to the JSONL produced by omp under agentRoot.
-- `parent_session_id` records a fork lineage (for branch UI).
CREATE TABLE sessions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL UNIQUE,            -- matches omp sessionId
    user_id BIGINT NOT NULL,
    repo_id VARCHAR(128) NOT NULL,
    omp_session_file VARCHAR(512) NULL,                -- null until first prompt
    parent_session_file VARCHAR(512) NULL,
    title VARCHAR(255) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'active',      -- 'active' | 'archived'
    model_provider VARCHAR(64) NULL,
    model_id VARCHAR(128) NULL,
    thinking_level VARCHAR(16) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_active_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_user (user_id, status),
    INDEX idx_repo (user_id, repo_id),
    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Prompt audit: every prompt the user sends.
CREATE TABLE prompt_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    prompt_text TEXT,
    prompt_images TEXT NULL,
    sent_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_session (session_id, sent_at),
    INDEX idx_user_time (user_id, sent_at)
);

-- Tool audit: every tool call (read/edit/write/bash/...) executed in the agent loop.
CREATE TABLE tool_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    tool_call_id VARCHAR(128) NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    arguments TEXT NULL,
    result TEXT NULL,
    is_error BOOLEAN NOT NULL DEFAULT FALSE,
    started_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    ended_at TIMESTAMP(3) NULL,
    INDEX idx_session (session_id),
    INDEX idx_tool (tool_name, started_at),
    INDEX idx_user_time (user_id, started_at)
);

-- Response audit: assistant turn summaries (one row per completed message).
CREATE TABLE response_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    message_id VARCHAR(128) NULL,
    full_text TEXT,
    thinking TEXT,
    is_error BOOLEAN NOT NULL DEFAULT FALSE,
    stop_reason VARCHAR(32) NULL,
    finished_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_session (session_id, finished_at)
);

-- Admin config: model + omp flags. Hot-loaded by AdminConfigService; new values apply to NEW processes.
-- Running processes need POST /admin/sessions/{id}/reload to pick up changes.
CREATE TABLE admin_config (
    config_key VARCHAR(128) PRIMARY KEY,
    config_value JSON NOT NULL,
    description VARCHAR(512) NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64) NULL
);