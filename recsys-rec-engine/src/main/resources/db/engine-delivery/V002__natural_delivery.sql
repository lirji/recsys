BEGIN;
CREATE TABLE engine_natural_delivery (
    operation_id UUID PRIMARY KEY,
    subject_key VARCHAR(512) NOT NULL CHECK (length(btrim(subject_key)) > 0),
    request_json TEXT NOT NULL CHECK (octet_length(request_json) <= 16384),
    policy_json TEXT NOT NULL CHECK (octet_length(policy_json) <= 1024),
    state VARCHAR(16) NOT NULL DEFAULT 'PREPARING' CHECK (state IN ('PREPARING','COMPLETE')),
    fence BIGINT NOT NULL DEFAULT 0 CHECK (fence >= 0),
    lease_until TIMESTAMPTZ,
    result_json TEXT CHECK (octet_length(result_json) <= 4194304),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((state='PREPARING' AND result_json IS NULL) OR (state='COMPLETE' AND result_json IS NOT NULL AND lease_until IS NULL))
);
COMMENT ON TABLE engine_natural_delivery IS 'engine 自有自然交付操作；完整资格响应提交后才可交付';
COMMENT ON COLUMN engine_natural_delivery.operation_id IS '客户端发送前持久化的整次操作 UUID';
COMMENT ON COLUMN engine_natural_delivery.subject_key IS '原认证主体，防止凭 UUID 越权';
COMMENT ON COLUMN engine_natural_delivery.request_json IS '不可变原请求，不含终端提供的分桶/资格';
COMMENT ON COLUMN engine_natural_delivery.policy_json IS '首次请求明确配置的自然政策，不受后续配置改变影响';
COMMENT ON COLUMN engine_natural_delivery.state IS '准备中或完整冻结，不代表自然曝光已接受';
COMMENT ON COLUMN engine_natural_delivery.fence IS '单调递增执行权，旧执行器不能覆盖新结果';
COMMENT ON COLUMN engine_natural_delivery.lease_until IS '数据库时钟的三十秒执行租约';
COMMENT ON COLUMN engine_natural_delivery.result_json IS '不可变最终内容、位置、分桶和签名资格；包含敏感票据';
COMMENT ON COLUMN engine_natural_delivery.created_at IS '原请求首次受理时间';
COMMENT ON COLUMN engine_natural_delivery.updated_at IS '最近领取或冻结时间';
CREATE INDEX engine_natural_pending_idx ON engine_natural_delivery(updated_at) WHERE state='PREPARING';
CREATE FUNCTION engine_natural_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR OLD.state='COMPLETE' THEN RAISE EXCEPTION '自然交付历史不可修改或删除'; END IF;
    IF (NEW.operation_id,NEW.subject_key,NEW.request_json,NEW.policy_json,NEW.created_at) IS DISTINCT FROM
       (OLD.operation_id,OLD.subject_key,OLD.request_json,OLD.policy_json,OLD.created_at) OR NEW.fence < OLD.fence THEN
        RAISE EXCEPTION '自然交付原请求和政策不可变，fence 不可倒退';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER engine_natural_immutable BEFORE UPDATE OR DELETE ON engine_natural_delivery
    FOR EACH ROW EXECUTE FUNCTION engine_natural_guard();
INSERT INTO engine_delivery_schema_history(version,description,script_sha256)
VALUES (2,'sandbox natural delivery',current_setting('recsys.migration_sha256'));
COMMIT;
