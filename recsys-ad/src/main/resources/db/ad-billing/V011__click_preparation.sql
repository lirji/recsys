BEGIN;
CREATE TABLE ad_click_preparation (
    child_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL,
    subject_key VARCHAR(200) NOT NULL,
    slot INTEGER NOT NULL CHECK (slot BETWEEN 1 AND 10),
    request_json TEXT NOT NULL CHECK (octet_length(request_json) <= 32768),
    result_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    UNIQUE(operation_id, slot),
    CHECK ((result_json IS NULL) = (completed_at IS NULL))
);
COMMENT ON TABLE ad_click_preparation IS 'sandbox 点击广告准备可恢复操作；无曝光和收费语义，首个结果不可变';
COMMENT ON COLUMN ad_click_preparation.child_id IS '候选 offerId，同时作为报价与预占稳定幂等标识';
COMMENT ON COLUMN ad_click_preparation.operation_id IS 'engine 整次交付标识';
COMMENT ON COLUMN ad_click_preparation.subject_key IS '原已认证交付主体';
COMMENT ON COLUMN ad_click_preparation.slot IS '已冻结最终位置，不因拒绝补位';
COMMENT ON COLUMN ad_click_preparation.request_json IS '服务端原始准备输入，含敏感签名证明，禁止日志输出';
COMMENT ON COLUMN ad_click_preparation.result_json IS '首次准备结果与资格证明，null 表示仍需恢复';
COMMENT ON COLUMN ad_click_preparation.created_at IS 'owner 数据库首次受理时间';
COMMENT ON COLUMN ad_click_preparation.completed_at IS '准备结果持久化时间';
CREATE INDEX ad_click_preparation_pending_idx ON ad_click_preparation(created_at) WHERE result_json IS NULL;
CREATE FUNCTION ad_click_preparation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'click preparation history is immutable'; END IF;
    IF (to_jsonb(NEW)-'result_json'-'completed_at') IS DISTINCT FROM (to_jsonb(OLD)-'result_json'-'completed_at')
       OR (OLD.result_json IS NOT NULL AND to_jsonb(NEW) IS DISTINCT FROM to_jsonb(OLD)) THEN
        RAISE EXCEPTION 'click preparation identity or result is immutable';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER ad_click_preparation_guard BEFORE UPDATE OR DELETE ON ad_click_preparation
FOR EACH ROW EXECUTE FUNCTION ad_click_preparation_guard();
INSERT INTO ad_billing_schema_history(version, description, script_sha256)
VALUES (11, 'sandbox click preparation', current_setting('recsys.migration_sha256'));
COMMIT;
