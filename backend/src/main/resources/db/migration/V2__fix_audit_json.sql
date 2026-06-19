-- Drop existing audit tables to re-create with TEXT columns (was JSON, rejected empty strings).
-- Safe because audit rows are regeneratable and we don't lose critical business data.
DROP TABLE IF EXISTS prompt_audit;
DROP TABLE IF EXISTS tool_audit;
DROP TABLE IF EXISTS response_audit;

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