BEGIN;
CREATE TABLE engine_feed_delivery (
    operation_id UUID PRIMARY KEY,
    subject_key VARCHAR(200) NOT NULL CHECK (length(btrim(subject_key))>0),
    request_json TEXT NOT NULL CHECK (octet_length(request_json)<=16384),
    settings_json TEXT NOT NULL CHECK (octet_length(settings_json)<=4096),
    state VARCHAR(16) NOT NULL DEFAULT 'PREPARING' CHECK (state IN ('PREPARING','COMPLETE')),
    fence BIGINT NOT NULL DEFAULT 0 CHECK (fence>=0),
    lease_until TIMESTAMPTZ,
    plan_json TEXT CHECK (octet_length(plan_json)<=4194304),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (state<>'COMPLETE' OR (plan_json IS NOT NULL AND lease_until IS NULL))
);
COMMENT ON TABLE engine_feed_delivery IS 'sandbox 最终混排操作；冻结后只补齐原广告子操作结果';
COMMENT ON COLUMN engine_feed_delivery.operation_id IS '客户端发送前保存的整次操作 UUID';
COMMENT ON COLUMN engine_feed_delivery.subject_key IS '原认证主体，不接受终端指定';
COMMENT ON COLUMN engine_feed_delivery.request_json IS '不可变原用户、搜索词、数量与场景';
COMMENT ON COLUMN engine_feed_delivery.settings_json IS '首次混排、广告实验参数和自然政策快照';
COMMENT ON COLUMN engine_feed_delivery.state IS '准备中可含已冻结自然结果；完成表示全部广告结论已知';
COMMENT ON COLUMN engine_feed_delivery.fence IS '每次领取递增，阻止旧执行器继续写';
COMMENT ON COLUMN engine_feed_delivery.lease_until IS '数据库时钟的有界执行租约';
COMMENT ON COLUMN engine_feed_delivery.plan_json IS '最终位置、自然票据、广告原候选与单调准备结果，含受限凭据';
COMMENT ON COLUMN engine_feed_delivery.created_at IS '首次受理数据库时间';
COMMENT ON COLUMN engine_feed_delivery.updated_at IS '最近领取、冻结或保存进度时间';
CREATE INDEX engine_feed_pending_idx ON engine_feed_delivery(updated_at) WHERE state='PREPARING';
CREATE FUNCTION engine_feed_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    old_plan JSONB;
    next_plan JSONB;
    previous_entry JSONB;
    next_entry JSONB;
    i INTEGER;
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'feed 历史不可删除'; END IF;
    IF TG_OP='UPDATE' THEN
        IF OLD.state='COMPLETE' OR (NEW.operation_id,NEW.subject_key,NEW.request_json,NEW.settings_json,NEW.created_at)
            IS DISTINCT FROM (OLD.operation_id,OLD.subject_key,OLD.request_json,OLD.settings_json,OLD.created_at)
            OR NEW.fence<OLD.fence THEN RAISE EXCEPTION 'feed 原上下文不可修改'; END IF;
        old_plan=OLD.plan_json::jsonb;
    END IF;
    next_plan=NEW.plan_json::jsonb;
    IF old_plan IS NOT NULL THEN
        IF next_plan IS NULL OR (old_plan-'entries') IS DISTINCT FROM (next_plan-'entries')
            OR jsonb_array_length(old_plan->'entries')<>jsonb_array_length(next_plan->'entries') THEN
            RAISE EXCEPTION 'feed 冻结计划不可替换';
        END IF;
        FOR i IN 0..jsonb_array_length(old_plan->'entries')-1 LOOP
            previous_entry=old_plan->'entries'->i; next_entry=next_plan->'entries'->i;
            IF (previous_entry-'preparation') IS DISTINCT FROM (next_entry-'preparation')
                OR (previous_entry->'preparation'<>'null'::jsonb AND previous_entry->'preparation' IS DISTINCT FROM next_entry->'preparation') THEN
                RAISE EXCEPTION 'feed 已冻结身份与结果不可修改';
            END IF;
        END LOOP;
    END IF;
    IF next_plan IS NOT NULL THEN
        IF jsonb_typeof(next_plan->'entries') IS DISTINCT FROM 'array'
            OR jsonb_array_length(next_plan->'entries')>(NEW.request_json::jsonb->>'size')::int THEN
            RAISE EXCEPTION 'feed 计划条数非法';
        END IF;
        FOR next_entry,i IN SELECT value,ordinality FROM jsonb_array_elements(next_plan->'entries') WITH ORDINALITY LOOP
            IF (next_entry->>'slot')::int IS DISTINCT FROM i
                OR ((next_entry->'natural' IS DISTINCT FROM 'null'::jsonb)=(next_entry->'candidate' IS DISTINCT FROM 'null'::jsonb))
                OR (next_entry->'candidate'<>'null'::jsonb AND i>10)
                OR (next_entry->'natural'<>'null'::jsonb AND (next_entry->'natural'->>'slot')::int IS DISTINCT FROM i)
                OR (NEW.state='COMPLETE' AND next_entry->'candidate'<>'null'::jsonb AND next_entry->'preparation'='null'::jsonb) THEN
                RAISE EXCEPTION 'feed 最终位置或完成状态非法';
            END IF;
        END LOOP;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER engine_feed_immutable BEFORE INSERT OR UPDATE OR DELETE ON engine_feed_delivery
    FOR EACH ROW EXECUTE FUNCTION engine_feed_guard();
INSERT INTO engine_delivery_schema_history(version,description,script_sha256)
VALUES (3,'sandbox final feed delivery',current_setting('recsys.migration_sha256'));
COMMIT;
