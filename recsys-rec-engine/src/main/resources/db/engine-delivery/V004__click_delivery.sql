BEGIN;
CREATE TABLE engine_click_delivery (
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
COMMENT ON TABLE engine_click_delivery IS 'sandbox 整次点击广告交付请求、冻结计划及逐项准备结果';
COMMENT ON COLUMN engine_click_delivery.operation_id IS '客户端持久化整次幂等操作 ID';
COMMENT ON COLUMN engine_click_delivery.subject_key IS '认证主体，不使用终端提供的 subject';
COMMENT ON COLUMN engine_click_delivery.request_json IS '不可变原始请求，包含模拟用户与查询';
COMMENT ON COLUMN engine_click_delivery.bucket IS '首次实验分桶快照';
COMMENT ON COLUMN engine_click_delivery.reserve IS '首次候选查询底价快照，精确结算仍由 ad quote 决定';
COMMENT ON COLUMN engine_click_delivery.state IS '准备中或整次准备完成，均不代表已曝光';
COMMENT ON COLUMN engine_click_delivery.fence IS '每次领取递增的防旧执行器写入令牌';
COMMENT ON COLUMN engine_click_delivery.lease_until IS '数据库时钟租约，过期后可由原 ID 恢复';
COMMENT ON COLUMN engine_click_delivery.plan_json IS '不可变候选/位置和可递增确认的子结果；null 表示尚未冻结';
COMMENT ON COLUMN engine_click_delivery.created_at IS '首次受理时间';
COMMENT ON COLUMN engine_click_delivery.updated_at IS '最近领取或进度提交时间';
CREATE INDEX engine_click_delivery_pending_idx ON engine_click_delivery(updated_at) WHERE state = 'PREPARING';
CREATE FUNCTION engine_click_delivery_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'click delivery history is immutable'; END IF;
    IF ROW(NEW.operation_id,NEW.subject_key,NEW.request_json,NEW.bucket,NEW.reserve,NEW.created_at)
       IS DISTINCT FROM ROW(OLD.operation_id,OLD.subject_key,OLD.request_json,OLD.bucket,OLD.reserve,OLD.created_at)
       OR (OLD.state='COMPLETE' AND to_jsonb(NEW) IS DISTINCT FROM to_jsonb(OLD)) THEN
        RAISE EXCEPTION 'click delivery identity or final result is immutable';
    END IF;
    IF OLD.plan_json IS NOT NULL THEN
        IF NEW.plan_json IS NULL OR jsonb_array_length(NEW.plan_json::jsonb)<>jsonb_array_length(OLD.plan_json::jsonb) THEN
            RAISE EXCEPTION 'click delivery plan is immutable';
        END IF;
        IF EXISTS (SELECT 1 FROM jsonb_array_elements(OLD.plan_json::jsonb) WITH ORDINALITY AS prior(entry,n)
            WHERE (prior.entry-'result') IS DISTINCT FROM ((NEW.plan_json::jsonb->(prior.n::int-1))-'result')
               OR (prior.entry->'result'<>'null'::jsonb AND prior.entry->'result' IS DISTINCT FROM NEW.plan_json::jsonb->(prior.n::int-1)->'result')) THEN
            RAISE EXCEPTION 'click delivery entries or results are immutable';
        END IF;
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER engine_click_delivery_guard BEFORE UPDATE OR DELETE ON engine_click_delivery
FOR EACH ROW EXECUTE FUNCTION engine_click_delivery_guard();
INSERT INTO engine_delivery_schema_history(version, description, script_sha256)
VALUES (4, 'sandbox click delivery', current_setting('recsys.migration_sha256'));
COMMIT;
