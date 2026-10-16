BEGIN;
-- 显式升级既有待发行；不能把历史未知发布标记自动解释为成功。
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM natural_impression_outbox WHERE published_at IS NOT NULL) THEN
        RAISE EXCEPTION '自然曝光旧发布状态异常，需先核查';
    END IF;
END $$;
INSERT INTO natural_exposure_schema_history(version,script_sha256)
VALUES (2,current_setting('recsys.migration_sha256'));

ALTER TABLE natural_impression_outbox
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'READY',
    ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 20),
    ADD COLUMN available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    ADD COLUMN fence BIGINT NOT NULL DEFAULT 0 CHECK (fence >= 0),
    ADD COLUMN last_error VARCHAR(32),
    ADD CONSTRAINT natural_outbox_delivery_state CHECK (
        status IN ('READY','SENDING','PUBLISHED','DEAD')
        AND ((status='PUBLISHED') = (published_at IS NOT NULL))
        AND (status NOT IN ('SENDING','PUBLISHED','DEAD') OR attempts > 0)
        AND (last_error IS NULL OR last_error IN
            ('INVALID_EVENT','BROKER_UNAVAILABLE','SEND_UNKNOWN','ATTEMPTS_EXHAUSTED')));
COMMENT ON COLUMN natural_impression_outbox.status IS '可靠投递状态；DEAD 保留原事件等待受控处置';
COMMENT ON COLUMN natural_impression_outbox.attempts IS '本轮已领取次数，含进程崩溃，最多二十次';
COMMENT ON COLUMN natural_impression_outbox.available_at IS 'READY 下次可领取时间或 SENDING 数据库租约到期时间';
COMMENT ON COLUMN natural_impression_outbox.fence IS '领取及审计重放递增版本，阻止旧执行器回写';
COMMENT ON COLUMN natural_impression_outbox.last_error IS '固定错误分类，不存异常消息或敏感负载';
COMMENT ON COLUMN natural_impression_outbox.published_at IS 'broker 确认后有效租约持有者标记的数据库时间';
CREATE INDEX natural_outbox_delivery_due_idx ON natural_impression_outbox(available_at,event_id)
    WHERE status IN ('READY','SENDING');

CREATE TABLE natural_impression_outbox_replay (
    replay_id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES natural_impression_outbox(event_id),
    actor VARCHAR(200) NOT NULL CHECK (length(trim(actor)) > 0),
    reason VARCHAR(1000) NOT NULL CHECK (length(trim(reason)) > 0),
    old_attempts INTEGER NOT NULL CHECK (old_attempts BETWEEN 1 AND 20),
    previous_fence BIGINT NOT NULL CHECK (previous_fence > 0),
    new_fence BIGINT NOT NULL CHECK (new_fence = previous_fence + 1),
    replayed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(event_id,new_fence)
);
COMMENT ON TABLE natural_impression_outbox_replay IS '自然事件受控重放命令与不可变审计；不改变事实和负载';
COMMENT ON COLUMN natural_impression_outbox_replay.replay_id IS '调用方稳定恢复命令 ID，同键不得改变内容';
COMMENT ON COLUMN natural_impression_outbox_replay.event_id IS '原事件身份';
COMMENT ON COLUMN natural_impression_outbox_replay.actor IS '可信调用边界提供的操作者，不能包含凭据';
COMMENT ON COLUMN natural_impression_outbox_replay.reason IS '恢复原因或工单说明';
COMMENT ON COLUMN natural_impression_outbox_replay.old_attempts IS '此次重开前已耗用领取次数';
COMMENT ON COLUMN natural_impression_outbox_replay.previous_fence IS '此次重开前的版本';
COMMENT ON COLUMN natural_impression_outbox_replay.new_fence IS '此次重开产生的新版本';
COMMENT ON COLUMN natural_impression_outbox_replay.replayed_at IS '数据库审计时间';
CREATE TRIGGER natural_outbox_replay_immutable BEFORE UPDATE OR DELETE ON natural_impression_outbox_replay
    FOR EACH ROW EXECUTE FUNCTION natural_reject_history_change();

-- 只放开发送生命周期；事实、回执及原事件身份仍保持不可变。
CREATE FUNCTION natural_outbox_delivery_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION '自然事件不得删除'; END IF;
    IF (NEW.event_id,NEW.event_type,NEW.schema_version,NEW.destination,NEW.partition_key,NEW.payload_json,NEW.created_at)
        IS DISTINCT FROM
       (OLD.event_id,OLD.event_type,OLD.schema_version,OLD.destination,OLD.partition_key,OLD.payload_json,OLD.created_at)
    THEN RAISE EXCEPTION '自然事件负载不可改变'; END IF;
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
    IF OLD.status='DEAD' AND NEW.status='READY' AND NEW.attempts=0 AND NEW.fence=OLD.fence+1
        AND NEW.last_error IS NULL AND NEW.published_at IS NULL
        AND EXISTS (SELECT 1 FROM natural_impression_outbox_replay r WHERE r.event_id=OLD.event_id
            AND r.previous_fence=OLD.fence AND r.new_fence=NEW.fence AND r.old_attempts=OLD.attempts)
    THEN RETURN NEW; END IF;
    RAISE EXCEPTION '自然事件投递状态迁移非法';
END $$;
DROP TRIGGER natural_outbox_immutable ON natural_impression_outbox;
CREATE TRIGGER natural_outbox_delivery_guard BEFORE UPDATE OR DELETE ON natural_impression_outbox
    FOR EACH ROW EXECUTE FUNCTION natural_outbox_delivery_guard();
COMMIT;
