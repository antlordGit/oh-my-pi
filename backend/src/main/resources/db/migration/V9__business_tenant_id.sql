-- ===========================================================================
-- V8: 业务数据表补充 tenant_id 列，实现三级数据隔离
--   - 超级管理员(super_admin)：查询全部数据（不按 user/tenant 过滤）
--   - 管理员(admin)：仅查询本租户数据
--   - 普通用户(user)：仅查询本人数据
-- 业务表此前只有 user_id，缺 tenant_id，无法在数据库层做租户隔离。
-- 此处补全字段，并按 user_id 关联 users 回填历史数据的租户归属。
-- ===========================================================================

-- 1. sessions
ALTER TABLE sessions ADD COLUMN tenant_id BIGINT NULL AFTER user_id;
ALTER TABLE sessions ADD INDEX idx_tenant (tenant_id);
UPDATE sessions s JOIN users u ON s.user_id = u.id SET s.tenant_id = u.tenant_id;

-- 2. repos
ALTER TABLE repos ADD COLUMN tenant_id BIGINT NULL AFTER user_id;
ALTER TABLE repos ADD INDEX idx_repo_tenant (tenant_id);
UPDATE repos r JOIN users u ON r.user_id = u.id SET r.tenant_id = u.tenant_id;

-- 3. prompt_audit
ALTER TABLE prompt_audit ADD COLUMN tenant_id BIGINT NULL AFTER user_id;
ALTER TABLE prompt_audit ADD INDEX idx_tenant_time (tenant_id, sent_at);
UPDATE prompt_audit a JOIN users u ON a.user_id = u.id SET a.tenant_id = u.tenant_id;

-- 4. tool_audit
ALTER TABLE tool_audit ADD COLUMN tenant_id BIGINT NULL AFTER user_id;
ALTER TABLE tool_audit ADD INDEX idx_tenant_time (tenant_id, started_at);
UPDATE tool_audit a JOIN users u ON a.user_id = u.id SET a.tenant_id = u.tenant_id;

-- 5. response_audit
ALTER TABLE response_audit ADD COLUMN tenant_id BIGINT NULL AFTER user_id;
ALTER TABLE response_audit ADD INDEX idx_tenant_time (tenant_id, finished_at);
UPDATE response_audit a JOIN users u ON a.user_id = u.id SET a.tenant_id = u.tenant_id;
