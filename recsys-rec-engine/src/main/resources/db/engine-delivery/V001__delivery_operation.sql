BEGIN;
CREATE TABLE engine_delivery_schema_history (
    version INTEGER PRIMARY KEY,
    description TEXT NOT NULL,
    script_sha256 CHAR(64) NOT NULL CHECK (script_sha256 ~ '^[0-9a-f]{64}$'),
    installed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE engine_delivery_schema_history IS 'engine 交付 owner 独立版本迁移历史';
COMMENT ON COLUMN engine_delivery_schema_history.version IS '不可改写的迁移版本';
COMMENT ON COLUMN engine_delivery_schema_history.description IS '迁移用途';
COMMENT ON COLUMN engine_delivery_schema_history.script_sha256 IS '实际执行脚本 SHA256';
COMMENT ON COLUMN engine_delivery_schema_history.installed_at IS '执行数据库时间';
CREATE TABLE engine_ad_delivery (
    operation_id UUID PRIMARY KEY,
    subject_key VARCHAR(200) NOT NULL CHECK (length(btrim(subject_key)) > 0),
    request_json TEXT NOT NULL CHECK (octet_length(request_json) <= 8192),
    bucket VARCHAR(200) NOT NULL,
    reserve DOUBLE PRECISION NOT NULL CHECK (reserve >= 0 AND reserve < 'Infinity'::float8),
    state VARCHAR(16) NOT NULL DEFAULT 'PREPARING' CHECK (state IN ('PREPARING', 'COMPLETE')),
    fence BIGINT NOT NULL DEFAULT 0 CHECK (fence >= 0),
    lease_until TIMESTAMPTZ,
    plan_json TEXT CHECK (octet_length(plan_json) <= 524288),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (state <> 'COMPLETE' OR (plan_json IS NOT NULL AND lease_until IS NULL))
);
COMMENT ON TABLE engine_ad_delivery IS 'sandbox 整次广告交付请求、冻结计划及逐项准备结果';
COMMENT ON COLUMN engine_ad_delivery.operation_id IS '客户端持久化整次幂等操作 ID';
COMMENT ON COLUMN engine_ad_delivery.subject_key IS '认证主体，不使用终端提供的 subject';
COMMENT ON COLUMN engine_ad_delivery.request_json IS '不可变原始请求，包含模拟用户与查询';
COMMENT ON COLUMN engine_ad_delivery.bucket IS '首次实验分桶快照';
COMMENT ON COLUMN engine_ad_delivery.reserve IS '首次候选查询底价快照，精确结算仍由 ad quote 决定';
COMMENT ON COLUMN engine_ad_delivery.state IS '准备中或整次准备完成，均不代表已曝光';
COMMENT ON COLUMN engine_ad_delivery.fence IS '每次领取递增的防旧执行器写入令牌';
COMMENT ON COLUMN engine_ad_delivery.lease_until IS '数据库时钟租约，过期后可由原 ID 恢复';
COMMENT ON COLUMN engine_ad_delivery.plan_json IS '不可变候选/位置和可递增确认的子结果；null 表示尚未冻结';
COMMENT ON COLUMN engine_ad_delivery.created_at IS '首次受理时间';
COMMENT ON COLUMN engine_ad_delivery.updated_at IS '最近领取或进度提交时间';
CREATE INDEX engine_ad_delivery_pending_idx ON engine_ad_delivery(updated_at) WHERE state = 'PREPARING';
INSERT INTO engine_delivery_schema_history(version, description, script_sha256)
VALUES (1, 'sandbox whole delivery', current_setting('recsys.migration_sha256'));
COMMIT;
