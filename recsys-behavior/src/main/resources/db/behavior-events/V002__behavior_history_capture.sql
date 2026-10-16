BEGIN;
-- 仅扩展本owner；原V001函数、源身份、快照和发送约束保持不变。
SELECT behavior_verify_source_schema();
INSERT INTO behavior_event_schema_history(version,script_sha256)
VALUES(2,current_setting('recsys.migration_sha256'));

CREATE TABLE behavior_history_command (
    command_id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES behavior_event_source(source_id),
    manifest_json TEXT NOT NULL CHECK (jsonb_typeof(manifest_json::jsonb)='array'),
    manifest_sha256 CHAR(64) NOT NULL CHECK (manifest_sha256=encode(sha256(convert_to(manifest_json,'UTF8')),'hex')),
    item_count INTEGER NOT NULL CHECK (item_count BETWEEN 1 AND 100 AND item_count=jsonb_array_length(manifest_json::jsonb)),
    actor VARCHAR(200) NOT NULL CHECK (length(btrim(actor))>0),
    reason VARCHAR(1000) NOT NULL CHECK (length(btrim(reason))>0),
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','COMPLETE','COMPLETED_WITH_ISSUES','CANCELLED')),
    visit_sequence BIGINT NOT NULL DEFAULT 0 CHECK (visit_sequence>=0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    CHECK ((status='OPEN')=(completed_at IS NULL))
);
CREATE TABLE behavior_history_item (
    command_id UUID NOT NULL REFERENCES behavior_history_command(command_id),
    behavior_id BIGINT NOT NULL CHECK (behavior_id>0),
    status VARCHAR(32) NOT NULL DEFAULT 'WAITING_SOURCE'
        CHECK (status IN ('WAITING_SOURCE','CAPTURED','ALREADY_CAPTURED','INVALID_SOURCE','SOURCE_CHANGED')),
    event_id TEXT REFERENCES behavior_event_outbox(event_id),
    error_code VARCHAR(32),
    last_visit BIGINT NOT NULL DEFAULT 0 CHECK (last_visit>=0),
    checked_at TIMESTAMPTZ,
    PRIMARY KEY(command_id,behavior_id),
    CHECK ((status='WAITING_SOURCE')=(event_id IS NULL)),
    CHECK ((last_visit=0)=(checked_at IS NULL)),
    CHECK ((status='WAITING_SOURCE' AND (error_code IS NULL OR error_code='SOURCE_NOT_VISIBLE'))
        OR (status IN ('CAPTURED','ALREADY_CAPTURED') AND error_code IS NULL)
        OR (status IN ('INVALID_SOURCE','SOURCE_CHANGED') AND error_code=status))
);
CREATE INDEX behavior_history_waiting_idx ON behavior_history_item(command_id,last_visit,behavior_id)
WHERE status='WAITING_SOURCE';
CREATE TABLE behavior_history_cancel (
    cancel_id UUID PRIMARY KEY,
    command_id UUID NOT NULL REFERENCES behavior_history_command(command_id),
    actor VARCHAR(200) NOT NULL CHECK (length(btrim(actor))>0),
    reason VARCHAR(1000) NOT NULL CHECK (length(btrim(reason))>0),
    previous_status VARCHAR(32) NOT NULL CHECK (previous_status IN ('OPEN','COMPLETE','COMPLETED_WITH_ISSUES','CANCELLED')),
    result VARCHAR(32) NOT NULL CHECK (result IN ('CANCELLED','ALREADY_TERMINAL')),
    cancelled_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((previous_status='OPEN')=(result='CANCELLED'))
);
CREATE TRIGGER behavior_history_cancel_immutable BEFORE UPDATE OR DELETE ON behavior_history_cancel
FOR EACH ROW EXECUTE FUNCTION behavior_event_immutable();

CREATE FUNCTION behavior_history_command_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION '历史命令不可删除'; END IF;
    IF (NEW.command_id,NEW.source_id,NEW.manifest_json,NEW.manifest_sha256,NEW.item_count,NEW.actor,NEW.reason,NEW.created_at)
        IS DISTINCT FROM (OLD.command_id,OLD.source_id,OLD.manifest_json,OLD.manifest_sha256,OLD.item_count,OLD.actor,OLD.reason,OLD.created_at)
        OR OLD.status<>'OPEN' THEN RAISE EXCEPTION '历史输入或终态不可改变'; END IF;
    IF NEW.status='OPEN' AND NEW.visit_sequence=OLD.visit_sequence+1 AND NEW.completed_at IS NULL THEN RETURN NEW; END IF;
    IF NEW.visit_sequence=OLD.visit_sequence AND NEW.completed_at IS NOT NULL THEN
        IF NEW.status='CANCELLED' AND EXISTS (SELECT 1 FROM behavior_history_cancel
            WHERE command_id=OLD.command_id AND result='CANCELLED') THEN RETURN NEW; END IF;
        IF NEW.status IN ('COMPLETE','COMPLETED_WITH_ISSUES')
            AND (SELECT count(*) FROM behavior_history_item WHERE command_id=OLD.command_id)=OLD.item_count
            AND NOT EXISTS (SELECT 1 FROM behavior_history_item WHERE command_id=OLD.command_id AND status='WAITING_SOURCE')
            AND (NEW.status='COMPLETED_WITH_ISSUES')=EXISTS (SELECT 1 FROM behavior_history_item
                WHERE command_id=OLD.command_id AND status IN ('INVALID_SOURCE','SOURCE_CHANGED')) THEN RETURN NEW; END IF;
    END IF;
    RAISE EXCEPTION '历史命令状态迁移非法';
END $$;
CREATE TRIGGER behavior_history_command_guard BEFORE UPDATE OR DELETE ON behavior_history_command
FOR EACH ROW EXECUTE FUNCTION behavior_history_command_guard();

CREATE FUNCTION behavior_history_item_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE parent behavior_history_command;
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION '历史清单不可删除'; END IF;
    SELECT * INTO parent FROM behavior_history_command WHERE command_id=NEW.command_id;
    IF parent.status IS DISTINCT FROM 'OPEN' THEN RAISE EXCEPTION '历史命令已终止'; END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.status='WAITING_SOURCE' AND NEW.last_visit=0 AND NEW.error_code IS NULL
            AND parent.manifest_json::jsonb @> jsonb_build_array(NEW.behavior_id) THEN RETURN NEW; END IF;
        RAISE EXCEPTION '历史清单输入非法';
    END IF;
    IF (NEW.command_id,NEW.behavior_id) IS DISTINCT FROM (OLD.command_id,OLD.behavior_id)
        OR OLD.status<>'WAITING_SOURCE' OR NEW.last_visit<=OLD.last_visit
        OR NEW.last_visit<>parent.visit_sequence OR NEW.checked_at IS NULL
        OR (NEW.event_id IS NOT NULL AND NEW.event_id<>parent.source_id::text||':user_behavior:'||NEW.behavior_id::text)
        THEN RAISE EXCEPTION '历史结果不可改变或访问序号非法'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER behavior_history_item_guard BEFORE INSERT OR UPDATE OR DELETE ON behavior_history_item
FOR EACH ROW EXECUTE FUNCTION behavior_history_item_guard();

COMMENT ON TABLE behavior_history_command IS '显式历史ID清单及持久公平续办状态，不代表全表覆盖';
COMMENT ON COLUMN behavior_history_command.command_id IS '调用方稳定恢复命令UUID';
COMMENT ON COLUMN behavior_history_command.source_id IS '本owner持久源身份';
COMMENT ON COLUMN behavior_history_command.manifest_json IS '排序且无重复的正BIGINT清单规范JSON';
COMMENT ON COLUMN behavior_history_command.manifest_sha256 IS '不可变规范清单UTF8摘要';
COMMENT ON COLUMN behavior_history_command.item_count IS '显式清单数量，上限100';
COMMENT ON COLUMN behavior_history_command.actor IS '受信任调用边界提供的操作者';
COMMENT ON COLUMN behavior_history_command.reason IS '不可变恢复原因';
COMMENT ON COLUMN behavior_history_command.status IS '仅清单捕获状态，不等同Kafka已投递';
COMMENT ON COLUMN behavior_history_command.visit_sequence IS '逐项事务递增的公平访问序号，不是源提交水位';
COMMENT ON COLUMN behavior_history_command.created_at IS '数据库登记时间';
COMMENT ON COLUMN behavior_history_command.completed_at IS '数据库完成或取消时间';
COMMENT ON TABLE behavior_history_item IS '显式清单逐项不可变终态及待源行的可续办记录';
COMMENT ON COLUMN behavior_history_item.command_id IS '所属历史命令';
COMMENT ON COLUMN behavior_history_item.behavior_id IS '原源行ID，不能推断提交顺序';
COMMENT ON COLUMN behavior_history_item.status IS '捕获、已有、隔离、漂移或等待源行';
COMMENT ON COLUMN behavior_history_item.event_id IS '原稳定事件身份，不因重叠清单重新生成';
COMMENT ON COLUMN behavior_history_item.error_code IS '闭合错误分类，不含源负载';
COMMENT ON COLUMN behavior_history_item.last_visit IS '上次已提交访问序号，未访问为零';
COMMENT ON COLUMN behavior_history_item.checked_at IS '本项最近已提交观察时间，终态后固定';
COMMENT ON TABLE behavior_history_cancel IS '幂等取消审计，只阻止后续捕获，不撤销已有事件';
COMMENT ON COLUMN behavior_history_cancel.cancel_id IS '独立稳定取消命令UUID';
COMMENT ON COLUMN behavior_history_cancel.command_id IS '目标历史命令';
COMMENT ON COLUMN behavior_history_cancel.actor IS '可信取消操作者';
COMMENT ON COLUMN behavior_history_cancel.reason IS '不可变取消原因';
COMMENT ON COLUMN behavior_history_cancel.previous_status IS '取消命令首次执行时的任务状态';
COMMENT ON COLUMN behavior_history_cancel.result IS '已取消或原任务已终态';
COMMENT ON COLUMN behavior_history_cancel.cancelled_at IS '数据库审计时间';
COMMIT;
