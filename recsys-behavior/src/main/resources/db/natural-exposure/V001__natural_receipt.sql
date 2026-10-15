BEGIN;
CREATE TABLE natural_exposure_schema_history (
    version INTEGER PRIMARY KEY,
    script_sha256 CHAR(64) NOT NULL CHECK (script_sha256 ~ '^[0-9a-f]{64}$'),
    installed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE natural_exposure_schema_history IS 'behavior 自然曝光自有迁移历史，不与 engine/ad 共用';
COMMENT ON COLUMN natural_exposure_schema_history.version IS '不可改写的版本';
COMMENT ON COLUMN natural_exposure_schema_history.script_sha256 IS '实际执行脚本 SHA256';
COMMENT ON COLUMN natural_exposure_schema_history.installed_at IS '迁移执行数据库时间';

CREATE TABLE natural_qualification_guard (
    qualification_id UUID PRIMARY KEY,
    subject_key VARCHAR(512) NOT NULL CHECK (length(btrim(subject_key)) > 0),
    context_hash CHAR(64) NOT NULL CHECK (context_hash ~ '^[0-9a-f]{64}$'),
    context_json TEXT NOT NULL CHECK (octet_length(context_json) <= 32768),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE natural_qualification_guard IS '已验签自然资格的不可变上下文与事务并发锁，不代表已接受曝光';
COMMENT ON COLUMN natural_qualification_guard.qualification_id IS '签名绑定的唯一资格 ID';
COMMENT ON COLUMN natural_qualification_guard.subject_key IS '原认证主体，仅 owner 查询';
COMMENT ON COLUMN natural_qualification_guard.context_hash IS '不含签名 keyId 的规范资格摘要，阻止同 ID 变语义';
COMMENT ON COLUMN natural_qualification_guard.context_json IS '完整已验证资格上下文，不保存原票据';
COMMENT ON COLUMN natural_qualification_guard.created_at IS '首次合法回执进入时间';

CREATE TABLE natural_impression_receipt (
    receipt_id UUID PRIMARY KEY,
    qualification_id UUID NOT NULL REFERENCES natural_qualification_guard(qualification_id) DEFERRABLE INITIALLY DEFERRED,
    subject_key VARCHAR(512) NOT NULL CHECK (length(btrim(subject_key)) > 0),
    payload_hash CHAR(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    visible_duration_ms INTEGER NOT NULL CHECK (visible_duration_ms BETWEEN 0 AND 300000),
    min_visible_ratio_bps INTEGER NOT NULL CHECK (min_visible_ratio_bps BETWEEN 0 AND 10000),
    page_visible BOOLEAN NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACCEPTED','ALREADY_ACCEPTED','POLICY_NOT_MET','EXPIRED')),
    event_id UUID,
    canonical_receipt_id UUID REFERENCES natural_impression_receipt(receipt_id),
    accepted_at TIMESTAMPTZ,
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((status = 'PENDING' AND decided_at IS NULL AND event_id IS NULL AND canonical_receipt_id IS NULL AND accepted_at IS NULL)
        OR (status IN ('POLICY_NOT_MET','EXPIRED') AND decided_at IS NOT NULL AND event_id IS NULL AND canonical_receipt_id IS NULL AND accepted_at IS NULL)
        OR (status IN ('ACCEPTED','ALREADY_ACCEPTED') AND decided_at IS NOT NULL AND event_id IS NOT NULL
            AND event_id = qualification_id AND canonical_receipt_id IS NOT NULL AND accepted_at IS NOT NULL)),
    CHECK (status <> 'ACCEPTED' OR canonical_receipt_id = receipt_id)
);
COMMENT ON TABLE natural_impression_receipt IS '自然回执幂等命令与不可变首次决定，无资金含义';
COMMENT ON COLUMN natural_impression_receipt.receipt_id IS '客户端在发送前持久化的 UUID';
COMMENT ON COLUMN natural_impression_receipt.qualification_id IS '验证后的资格，不接受客户端另传 item';
COMMENT ON COLUMN natural_impression_receipt.subject_key IS '认证主体，防止跨主体查询/重试';
COMMENT ON COLUMN natural_impression_receipt.payload_hash IS '原命令二进制摘要，防同键改负载';
COMMENT ON COLUMN natural_impression_receipt.visible_duration_ms IS '客户端声明连续可见毫秒';
COMMENT ON COLUMN natural_impression_receipt.min_visible_ratio_bps IS '连续区间最小可见基点';
COMMENT ON COLUMN natural_impression_receipt.page_visible IS '冻结观测时页面是否可见';
COMMENT ON COLUMN natural_impression_receipt.status IS '首次决定；PENDING 只能存在于未提交事务';
COMMENT ON COLUMN natural_impression_receipt.event_id IS '接受时等于 qualification_id，拒绝为空';
COMMENT ON COLUMN natural_impression_receipt.canonical_receipt_id IS '唯一首次接受的原 receiptId';
COMMENT ON COLUMN natural_impression_receipt.accepted_at IS '原事实接受时间，拒绝为空';
COMMENT ON COLUMN natural_impression_receipt.decided_at IS '本回执首次决定数据库时间';
COMMENT ON COLUMN natural_impression_receipt.created_at IS '命令首次进入数据库时间';
CREATE INDEX natural_receipt_qualification_idx ON natural_impression_receipt(qualification_id);

CREATE TABLE natural_impression_fact (
    qualification_id UUID PRIMARY KEY REFERENCES natural_qualification_guard(qualification_id),
    receipt_id UUID NOT NULL UNIQUE REFERENCES natural_impression_receipt(receipt_id),
    traffic_class VARCHAR(16) NOT NULL CHECK (traffic_class = 'SANDBOX'),
    accepted_at TIMESTAMPTZ NOT NULL,
    UNIQUE (qualification_id,receipt_id,accepted_at)
);
COMMENT ON TABLE natural_impression_fact IS '唯一不可变自然曝光事实；完整关联上下文归资格 guard，不混旧 user_behavior';
COMMENT ON COLUMN natural_impression_fact.qualification_id IS '业务幂等身份，同时为 eventId';
COMMENT ON COLUMN natural_impression_fact.receipt_id IS '首次产生该事实的回执';
COMMENT ON COLUMN natural_impression_fact.traffic_class IS '固定 SANDBOX，禁止进入正式分母';
COMMENT ON COLUMN natural_impression_fact.accepted_at IS '持有资格锁后测量的服务端数据库接受时间';
ALTER TABLE natural_impression_receipt ADD CONSTRAINT natural_receipt_event_fk
    FOREIGN KEY (event_id,canonical_receipt_id,accepted_at) REFERENCES natural_impression_fact(qualification_id,receipt_id,accepted_at) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE natural_impression_outbox (
    event_id UUID PRIMARY KEY REFERENCES natural_impression_fact(qualification_id),
    event_type VARCHAR(64) NOT NULL CHECK (event_type = 'NaturalImpressionAcceptedV1'),
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    destination VARCHAR(100) NOT NULL CHECK (destination = 'natural-impression-accepted-v1'),
    partition_key VARCHAR(32) NOT NULL CHECK (partition_key ~ '^[1-9][0-9]{0,18}$'),
    payload_json TEXT NOT NULL CHECK (octet_length(payload_json) <= 32768),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    published_at TIMESTAMPTZ
);
COMMENT ON TABLE natural_impression_outbox IS '与自然事实原子提交的待发事件；本版本没有尽力发送器，可靠发布归 S15';
COMMENT ON COLUMN natural_impression_outbox.event_id IS '稳定事件身份，等于资格 ID';
COMMENT ON COLUMN natural_impression_outbox.event_type IS '明确自然接受事实类型';
COMMENT ON COLUMN natural_impression_outbox.schema_version IS '兼容契约版本';
COMMENT ON COLUMN natural_impression_outbox.destination IS '可靠发布逻辑 topic';
COMMENT ON COLUMN natural_impression_outbox.partition_key IS '原 userId 十进制，不承诺跨用户全局有序';
COMMENT ON COLUMN natural_impression_outbox.payload_json IS '不可变自然事件，无认证主体与票据';
COMMENT ON COLUMN natural_impression_outbox.created_at IS '待发进入数据库时间';
COMMENT ON COLUMN natural_impression_outbox.published_at IS '可靠发布确认时间；当前首版保持空';
ALTER TABLE natural_impression_fact ADD CONSTRAINT natural_fact_outbox_fk
    FOREIGN KEY (qualification_id) REFERENCES natural_impression_outbox(event_id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX natural_outbox_pending_idx ON natural_impression_outbox(created_at,event_id) WHERE published_at IS NULL;

CREATE FUNCTION natural_reject_history_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '自然曝光历史不可修改或删除';
END;
$$;
CREATE TRIGGER natural_guard_immutable BEFORE UPDATE OR DELETE ON natural_qualification_guard
    FOR EACH ROW EXECUTE FUNCTION natural_reject_history_change();
CREATE TRIGGER natural_fact_immutable BEFORE UPDATE OR DELETE ON natural_impression_fact
    FOR EACH ROW EXECUTE FUNCTION natural_reject_history_change();
CREATE TRIGGER natural_outbox_immutable BEFORE UPDATE OR DELETE ON natural_impression_outbox
    FOR EACH ROW EXECUTE FUNCTION natural_reject_history_change();
CREATE FUNCTION natural_receipt_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION '自然回执首次结果不可修改或删除';
    END IF;
    IF (NEW.receipt_id,NEW.qualification_id,NEW.subject_key,NEW.payload_hash,NEW.visible_duration_ms,
        NEW.min_visible_ratio_bps,NEW.page_visible,NEW.created_at) IS DISTINCT FROM
       (OLD.receipt_id,OLD.qualification_id,OLD.subject_key,OLD.payload_hash,OLD.visible_duration_ms,
        OLD.min_visible_ratio_bps,OLD.page_visible,OLD.created_at) THEN
        RAISE EXCEPTION '自然回执原命令不可修改';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER natural_receipt_immutable BEFORE UPDATE OR DELETE ON natural_impression_receipt
    FOR EACH ROW EXECUTE FUNCTION natural_receipt_guard();
CREATE FUNCTION natural_receipt_must_complete() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM natural_impression_receipt WHERE receipt_id = NEW.receipt_id AND status = 'PENDING') THEN
        RAISE EXCEPTION '自然回执不得提交半成品';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER natural_receipt_complete AFTER INSERT OR UPDATE ON natural_impression_receipt
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION natural_receipt_must_complete();
INSERT INTO natural_exposure_schema_history(version,script_sha256) VALUES (1,current_setting('recsys.migration_sha256'));
COMMIT;
