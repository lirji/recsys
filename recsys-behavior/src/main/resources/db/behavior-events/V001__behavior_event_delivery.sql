BEGIN;
-- 预检真实列、主键及部分唯一谓词；同名索引不代表具备CLICK约束。
CREATE FUNCTION behavior_verify_source_schema() RETURNS BOOLEAN LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM (VALUES ('id','bigint'),('user_id','bigint'),('item_id','bigint'),
        ('action','text'),('value','double precision'),('scene','text'),('bucket','text'),
        ('position','integer'),('exposure_id','text'),('ts','timestamp without time zone')) expected(name,kind)
        LEFT JOIN pg_attribute a ON a.attrelid='user_behavior'::regclass AND a.attname=expected.name AND NOT a.attisdropped
        WHERE a.attname IS NULL OR format_type(a.atttypid,a.atttypmod)<>expected.kind)
      OR NOT EXISTS (SELECT 1 FROM pg_index i JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=i.indkey[0]
        WHERE i.indrelid='user_behavior'::regclass AND i.indisprimary AND i.indisvalid AND i.indnkeyatts=1 AND a.attname='id')
      OR NOT EXISTS (SELECT 1 FROM pg_index i JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=i.indkey[0]
        WHERE i.indrelid='user_behavior'::regclass AND i.indisunique AND i.indisvalid AND i.indisready
        AND i.indnkeyatts=1 AND a.attname='exposure_id' AND i.indexprs IS NULL
        AND regexp_replace(replace(pg_get_expr(i.indpred,i.indrelid),'::text',''),'[()[:space:]]','','g')
            = 'action=''CLICK''ANDexposure_idISNOTNULL') THEN
        RAISE EXCEPTION '行为源表或CLICK约束不满足可靠捕获要求';
    END IF;
    RETURN TRUE;
END $$;
SELECT behavior_verify_source_schema();
CREATE TABLE behavior_event_schema_history (
    version INTEGER PRIMARY KEY CHECK (version>0),
    script_sha256 CHAR(64) NOT NULL CHECK (script_sha256 ~ '^[0-9a-f]{64}$'),
    applied_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE behavior_event_source (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    source_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO behavior_event_schema_history(version,script_sha256) VALUES(1,current_setting('recsys.migration_sha256'));
INSERT INTO behavior_event_source(singleton) VALUES(TRUE);

-- 固定字段和无时区表示；大字段在数据库内保存，不为隔离而拉到worker内存。
CREATE FUNCTION behavior_source_snapshot(b user_behavior) RETURNS TEXT LANGUAGE SQL IMMUTABLE AS $$
 SELECT jsonb_build_object('id',b.id,'user_id',b.user_id,'item_id',b.item_id,'action',b.action,
    'value',b.value,'scene',b.scene,'bucket',b.bucket,'position',b.position,'exposure_id',b.exposure_id,
    'ts',to_char(b.ts,'YYYY-MM-DD"T"HH24:MI:SS.US'))::text
$$;
CREATE TABLE behavior_event_outbox (
    event_id TEXT PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES behavior_event_source(source_id),
    behavior_id BIGINT NOT NULL CHECK (behavior_id>0),
    event_type TEXT NOT NULL DEFAULT 'LegacyBehaviorAcceptedV1' CHECK (event_type='LegacyBehaviorAcceptedV1'),
    schema_version INTEGER NOT NULL DEFAULT 1 CHECK (schema_version=1),
    origin TEXT NOT NULL CHECK (origin IN ('BEHAVIOR_POST','LEGACY_ROW')),
    destination TEXT NOT NULL,
    partition_key TEXT,
    source_snapshot_json TEXT NOT NULL,
    source_snapshot_sha256 CHAR(64) NOT NULL,
    wire_payload_json TEXT,
    wire_payload_sha256 CHAR(64),
    capture_reason TEXT NOT NULL CHECK (capture_reason IN ('ONLINE_ACCEPT','CLICK_DUPLICATE_REPAIR','HISTORY_COMMAND')),
    created_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'READY' CHECK (status IN ('READY','SENDING','PUBLISHED','DEAD')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 20),
    available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    fence BIGINT NOT NULL DEFAULT 0 CHECK (fence>=0),
    last_error VARCHAR(32),
    published_at TIMESTAMPTZ,
    UNIQUE(source_id,behavior_id),
    CHECK (event_id=source_id::text||':user_behavior:'||behavior_id::text),
    CHECK ((origin='BEHAVIOR_POST' AND destination='behavior-accepted-v1' AND capture_reason='ONLINE_ACCEPT')
        OR (origin='LEGACY_ROW' AND destination='behavior-history-recovery-v1' AND capture_reason IN ('CLICK_DUPLICATE_REPAIR','HISTORY_COMMAND'))),
    CHECK (source_snapshot_sha256=encode(sha256(convert_to(source_snapshot_json,'UTF8')),'hex')),
    CHECK ((wire_payload_json IS NULL AND wire_payload_sha256 IS NULL AND status='DEAD' AND last_error='INVALID_SOURCE' AND attempts=0 AND fence=0)
        OR (wire_payload_json IS NOT NULL AND wire_payload_sha256 IS NOT NULL AND partition_key IS NOT NULL
            AND partition_key ~ '^[1-9][0-9]*$' AND octet_length(wire_payload_json)<=32768
            AND wire_payload_sha256=encode(sha256(convert_to(wire_payload_json,'UTF8')),'hex')
            AND last_error IS DISTINCT FROM 'INVALID_SOURCE')),
    CHECK ((status='PUBLISHED')=(published_at IS NOT NULL)),
    CHECK (status NOT IN ('SENDING','PUBLISHED') OR attempts>0),
    CHECK (status<>'DEAD' OR attempts>0 OR last_error='INVALID_SOURCE'),
    CHECK (last_error IS NULL OR last_error IN ('INVALID_SOURCE','INVALID_EVENT','BROKER_UNAVAILABLE','SEND_UNKNOWN','ATTEMPTS_EXHAUSTED'))
);
CREATE INDEX behavior_event_due_idx ON behavior_event_outbox(destination,available_at,event_id) WHERE status IN ('READY','SENDING');
CREATE TABLE behavior_event_replay (
    replay_id UUID PRIMARY KEY,
    event_id TEXT NOT NULL REFERENCES behavior_event_outbox(event_id),
    actor VARCHAR(200) NOT NULL CHECK (length(trim(actor))>0),
    reason VARCHAR(1000) NOT NULL CHECK (length(trim(reason))>0),
    old_attempts INTEGER NOT NULL CHECK (old_attempts BETWEEN 1 AND 20),
    previous_fence BIGINT NOT NULL CHECK (previous_fence>0),
    new_fence BIGINT NOT NULL CHECK (new_fence=previous_fence+1),
    replayed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(event_id,new_fence)
);
CREATE FUNCTION behavior_event_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION '行为事件源身份或审计不可改变'; END $$;
CREATE TRIGGER behavior_source_immutable BEFORE UPDATE OR DELETE ON behavior_event_source FOR EACH ROW EXECUTE FUNCTION behavior_event_immutable();
CREATE TRIGGER behavior_schema_immutable BEFORE UPDATE OR DELETE ON behavior_event_schema_history FOR EACH ROW EXECUTE FUNCTION behavior_event_immutable();
CREATE TRIGGER behavior_replay_immutable BEFORE UPDATE OR DELETE ON behavior_event_replay FOR EACH ROW EXECUTE FUNCTION behavior_event_immutable();
CREATE FUNCTION behavior_event_delivery_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION '行为事件不得删除'; END IF;
    IF (NEW.event_id,NEW.source_id,NEW.behavior_id,NEW.event_type,NEW.schema_version,NEW.origin,NEW.destination,NEW.partition_key,NEW.source_snapshot_json,NEW.source_snapshot_sha256,NEW.wire_payload_json,NEW.wire_payload_sha256,NEW.capture_reason,NEW.created_at) IS DISTINCT FROM
       (OLD.event_id,OLD.source_id,OLD.behavior_id,OLD.event_type,OLD.schema_version,OLD.origin,OLD.destination,OLD.partition_key,OLD.source_snapshot_json,OLD.source_snapshot_sha256,OLD.wire_payload_json,OLD.wire_payload_sha256,OLD.capture_reason,OLD.created_at)
    THEN RAISE EXCEPTION '行为事件身份与快照不可改变'; END IF;
    IF OLD.status IN ('READY','SENDING') AND OLD.available_at<=clock_timestamp()
        AND OLD.attempts<20 AND NEW.status='SENDING'
        AND NEW.attempts=OLD.attempts+1 AND NEW.fence=OLD.fence+1
        AND NEW.available_at>clock_timestamp() AND NEW.published_at IS NULL
        AND NEW.last_error IS NOT DISTINCT FROM OLD.last_error THEN RETURN NEW; END IF;
    IF OLD.status='SENDING' AND OLD.available_at>clock_timestamp()
        AND NEW.attempts=OLD.attempts AND NEW.fence=OLD.fence THEN
        IF NEW.status='PUBLISHED' AND NEW.published_at IS NOT NULL AND NEW.last_error IS NULL
            AND NEW.available_at=OLD.available_at THEN RETURN NEW; END IF;
        IF NEW.published_at IS NULL AND NEW.last_error IN ('INVALID_EVENT','BROKER_UNAVAILABLE','SEND_UNKNOWN')
            AND ((NEW.status='READY' AND OLD.attempts<20 AND NEW.last_error<>'INVALID_EVENT')
              OR (NEW.status='DEAD' AND (OLD.attempts=20 OR NEW.last_error='INVALID_EVENT')))
        THEN RETURN NEW; END IF;
    END IF;
    IF OLD.status IN ('READY','SENDING') AND OLD.attempts=20 AND OLD.available_at<=clock_timestamp()
        AND NEW.status='DEAD' AND NEW.attempts=OLD.attempts AND NEW.fence=OLD.fence
        AND NEW.available_at=OLD.available_at AND NEW.published_at IS NULL
        AND NEW.last_error='ATTEMPTS_EXHAUSTED' THEN RETURN NEW; END IF;
    IF OLD.status='DEAD' AND OLD.wire_payload_json IS NOT NULL AND OLD.last_error<>'INVALID_SOURCE' AND NEW.status='READY' AND NEW.attempts=0 AND NEW.fence=OLD.fence+1
        AND NEW.last_error IS NULL AND NEW.published_at IS NULL
        AND EXISTS (SELECT 1 FROM behavior_event_replay r WHERE r.event_id=OLD.event_id
            AND r.previous_fence=OLD.fence AND r.new_fence=NEW.fence AND r.old_attempts=OLD.attempts)
    THEN RETURN NEW; END IF;
    RAISE EXCEPTION '行为事件投递状态迁移非法';
END $$;
CREATE TRIGGER behavior_event_delivery_guard BEFORE UPDATE OR DELETE ON behavior_event_outbox
    FOR EACH ROW EXECUTE FUNCTION behavior_event_delivery_guard();
COMMENT ON TABLE behavior_event_schema_history IS '行为可靠事件独立迁移记录';
COMMENT ON COLUMN behavior_event_schema_history.version IS '迁移版本';
COMMENT ON COLUMN behavior_event_schema_history.script_sha256 IS '打包脚本SHA256';
COMMENT ON COLUMN behavior_event_schema_history.applied_at IS '数据库执行时间';
COMMENT ON TABLE behavior_event_source IS '本逻辑源库不可变身份，克隆恢复沿用原身份';
COMMENT ON COLUMN behavior_event_source.singleton IS '固定单行约束';
COMMENT ON COLUMN behavior_event_source.source_id IS '独立逻辑源UUID';
COMMENT ON COLUMN behavior_event_source.created_at IS '身份创建时间';
COMMENT ON TABLE behavior_event_outbox IS '行为接受时的不可变快照与可靠发送义务';
COMMENT ON COLUMN behavior_event_outbox.event_id IS '稳定事件身份';
COMMENT ON COLUMN behavior_event_outbox.source_id IS '源库UUID';
COMMENT ON COLUMN behavior_event_outbox.behavior_id IS '源行BIGINT身份，非提交顺序';
COMMENT ON COLUMN behavior_event_outbox.event_type IS '事件类型';
COMMENT ON COLUMN behavior_event_outbox.schema_version IS '消息版本';
COMMENT ON COLUMN behavior_event_outbox.origin IS '捕获来源，历史不推断writer';
COMMENT ON COLUMN behavior_event_outbox.destination IS '逻辑目标流，禁止串流';
COMMENT ON COLUMN behavior_event_outbox.partition_key IS '用户分区键';
COMMENT ON COLUMN behavior_event_outbox.source_snapshot_json IS '原始行固定字段JSON文本，含无时区时间';
COMMENT ON COLUMN behavior_event_outbox.source_snapshot_sha256 IS '原快照UTF8摘要';
COMMENT ON COLUMN behavior_event_outbox.wire_payload_json IS '可发送原始字节，非法源为NULL';
COMMENT ON COLUMN behavior_event_outbox.wire_payload_sha256 IS '发送原文UTF8摘要';
COMMENT ON COLUMN behavior_event_outbox.capture_reason IS '捕获原因';
COMMENT ON COLUMN behavior_event_outbox.created_at IS '数据库捕获时间';
COMMENT ON COLUMN behavior_event_outbox.status IS '持久发送状态';
COMMENT ON COLUMN behavior_event_outbox.attempts IS '本轮领取次数含崩溃，最多20';
COMMENT ON COLUMN behavior_event_outbox.available_at IS '待发时间或30秒数据库租约';
COMMENT ON COLUMN behavior_event_outbox.fence IS '领取与重放版本';
COMMENT ON COLUMN behavior_event_outbox.last_error IS '固定错误分类，无敏感原文';
COMMENT ON COLUMN behavior_event_outbox.published_at IS '有效租约下broker确认标记时间';
COMMENT ON TABLE behavior_event_replay IS '行为合法DEAD原事件的不可变恢复命令审计';
COMMENT ON COLUMN behavior_event_replay.replay_id IS '稳定命令UUID';
COMMENT ON COLUMN behavior_event_replay.event_id IS '原事件身份';
COMMENT ON COLUMN behavior_event_replay.actor IS '可信调用方操作者';
COMMENT ON COLUMN behavior_event_replay.reason IS '审计原因';
COMMENT ON COLUMN behavior_event_replay.old_attempts IS '重开前次数';
COMMENT ON COLUMN behavior_event_replay.previous_fence IS '重开前版本';
COMMENT ON COLUMN behavior_event_replay.new_fence IS '重开后版本';
COMMENT ON COLUMN behavior_event_replay.replayed_at IS '数据库审计时间';
COMMIT;
