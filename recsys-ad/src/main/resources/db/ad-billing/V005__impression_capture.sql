-- 扩展迁移先于 receipts-enabled；已有 V001～V004 不改写。
BEGIN;
INSERT INTO ad_billing_schema_history(version, description, script_sha256)
VALUES (5, 'sandbox visible receipt capture ledger outbox', current_setting('recsys.migration_sha256'));

ALTER TABLE ad_budget_reservation DROP CONSTRAINT ad_budget_reservation_state_check;
ALTER TABLE ad_budget_reservation DROP CONSTRAINT ad_budget_reservation_close_reason_check;
ALTER TABLE ad_budget_reservation DROP CONSTRAINT ad_budget_reservation_check1;
ALTER TABLE ad_budget_reservation DROP CONSTRAINT ad_budget_reservation_check2;
ALTER TABLE ad_budget_reservation ADD CONSTRAINT ad_reservation_state_v2 CHECK (state IN ('PENDING','HELD','REJECTED','RELEASED','EXPIRED','CAPTURED'));
ALTER TABLE ad_budget_reservation ADD CONSTRAINT ad_reservation_reason_v2 CHECK (close_reason IN ('DELIVERY_ABORTED','EXPIRED','IMPRESSION_CAPTURED'));
ALTER TABLE ad_budget_reservation ADD CONSTRAINT ad_reservation_decision_v2 CHECK (
    (state='PENDING' AND decision IS NULL)
    OR (state IN ('HELD','RELEASED','EXPIRED','CAPTURED') AND decision='HELD')
    OR (state='REJECTED' AND decision IN ('INSUFFICIENT_BUDGET','QUOTE_EXPIRED','ACCOUNT_MISSING')));
ALTER TABLE ad_budget_reservation ADD CONSTRAINT ad_reservation_closed_v2 CHECK (
    (state IN ('PENDING','HELD','REJECTED') AND closed_at IS NULL AND close_reason IS NULL)
    OR (state='RELEASED' AND closed_at IS NOT NULL AND close_reason IS NOT NULL AND close_reason='DELIVERY_ABORTED')
    OR (state='EXPIRED' AND closed_at IS NOT NULL AND close_reason IS NOT NULL AND close_reason='EXPIRED')
    OR (state='CAPTURED' AND closed_at IS NOT NULL AND close_reason IS NOT NULL AND close_reason='IMPRESSION_CAPTURED'));
COMMENT ON COLUMN ad_budget_reservation.state IS '当前状态含已捕获 CAPTURED；PENDING 只能存在于未提交事务';

CREATE TABLE ad_impression_receipt (
    receipt_id UUID PRIMARY KEY,
    qualification_id UUID NOT NULL REFERENCES ad_delivery_preparation(child_id),
    subject_key VARCHAR(200) NOT NULL,
    payload_hash CHAR(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    visible_duration_ms BIGINT NOT NULL CHECK (visible_duration_ms BETWEEN 0 AND 300000),
    min_visible_ratio_bps INTEGER NOT NULL CHECK (min_visible_ratio_bps BETWEEN 0 AND 10000),
    page_visible BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACCEPTED','ALREADY_ACCEPTED','POLICY_NOT_MET','EXPIRED','NOT_HELD')),
    ledger_id UUID,
    canonical_receipt_id UUID REFERENCES ad_impression_receipt(receipt_id),
    charged_amount NUMERIC(20,6) NOT NULL DEFAULT 0 CHECK (charged_amount BETWEEN 0 AND 99999999999999.999999),
    budget_date DATE,
    accepted_at TIMESTAMPTZ,
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((status='PENDING') = (decided_at IS NULL)),
    CHECK ((status IN ('ACCEPTED','ALREADY_ACCEPTED') AND ledger_id IS NOT NULL AND canonical_receipt_id IS NOT NULL AND budget_date IS NOT NULL AND accepted_at IS NOT NULL)
        OR (status IN ('PENDING','POLICY_NOT_MET','EXPIRED','NOT_HELD') AND ledger_id IS NULL AND canonical_receipt_id IS NULL AND charged_amount=0 AND budget_date IS NULL AND accepted_at IS NULL))
);
COMMENT ON TABLE ad_impression_receipt IS 'sandbox 可见回执首次决策，拒绝亦幂等；不存原 token';
COMMENT ON COLUMN ad_impression_receipt.receipt_id IS '调用方稳定生成的事件标识';
COMMENT ON COLUMN ad_impression_receipt.qualification_id IS '已持久化准备的资格标识';
COMMENT ON COLUMN ad_impression_receipt.subject_key IS '认证主体用于查询权限及审计';
COMMENT ON COLUMN ad_impression_receipt.payload_hash IS '规范化完整命令摘要，含主体和证明';
COMMENT ON COLUMN ad_impression_receipt.visible_duration_ms IS '客户端声明连续可见毫秒数';
COMMENT ON COLUMN ad_impression_receipt.min_visible_ratio_bps IS '连续期间最小可见面积基点，10000 表示全可见';
COMMENT ON COLUMN ad_impression_receipt.page_visible IS '客户端声明页面处于可见状态';
COMMENT ON COLUMN ad_impression_receipt.status IS '不可变首次结果；PENDING 仅允许未提交事务内';
COMMENT ON COLUMN ad_impression_receipt.ledger_id IS '原唯一捕获账本标识，拒绝为空';
COMMENT ON COLUMN ad_impression_receipt.canonical_receipt_id IS '最初触发捕获的回执 ID，重复引用它';
COMMENT ON COLUMN ad_impression_receipt.charged_amount IS '原账本金额元，拒绝为零';
COMMENT ON COLUMN ad_impression_receipt.budget_date IS '原预占业务日，拒绝为空';
COMMENT ON COLUMN ad_impression_receipt.accepted_at IS '原捕获接受时间，拒绝为空';
COMMENT ON COLUMN ad_impression_receipt.decided_at IS '本回执首次决策数据库时间';
COMMENT ON COLUMN ad_impression_receipt.created_at IS '首次领取数据库时间';
CREATE INDEX ad_impression_receipt_qualification_idx ON ad_impression_receipt(qualification_id);

CREATE TABLE ad_billing_ledger (
    ledger_id UUID PRIMARY KEY,
    qualification_id UUID NOT NULL UNIQUE REFERENCES ad_budget_reservation(reservation_id),
    receipt_id UUID NOT NULL UNIQUE REFERENCES ad_impression_receipt(receipt_id),
    operation_id UUID NOT NULL,
    advertiser_id BIGINT NOT NULL CHECK (advertiser_id>0),
    ad_id BIGINT NOT NULL CHECK (ad_id>0),
    creative_id BIGINT NOT NULL CHECK (creative_id>=0),
    user_id BIGINT NOT NULL CHECK (user_id>0),
    slot INTEGER NOT NULL CHECK (slot BETWEEN 1 AND 10),
    traffic_class VARCHAR(16) NOT NULL CHECK (traffic_class='SANDBOX'),
    currency CHAR(3) NOT NULL CHECK (currency='CNY'),
    budget_date DATE NOT NULL,
    amount NUMERIC(20,6) NOT NULL CHECK (amount BETWEEN 0 AND 99999999999999.999999),
    account_version BIGINT NOT NULL CHECK (account_version>0),
    accepted_at TIMESTAMPTZ NOT NULL
);
COMMENT ON TABLE ad_billing_ledger IS '不可变 sandbox 图文 CPM 捕获事实；一资格最多一账，不能改删历史';
COMMENT ON COLUMN ad_billing_ledger.ledger_id IS '账本事实 ID，也是 Outbox eventId';
COMMENT ON COLUMN ad_billing_ledger.qualification_id IS '唯一被捕获预占/资格标识';
COMMENT ON COLUMN ad_billing_ledger.receipt_id IS '原触发捕获回执';
COMMENT ON COLUMN ad_billing_ledger.operation_id IS 'engine 整次交付 ID';
COMMENT ON COLUMN ad_billing_ledger.advertiser_id IS '冻结报价广告主';
COMMENT ON COLUMN ad_billing_ledger.ad_id IS '冻结广告标识';
COMMENT ON COLUMN ad_billing_ledger.creative_id IS '冻结创意标识，零为默认创意';
COMMENT ON COLUMN ad_billing_ledger.user_id IS '服务端签名资格中的模拟用户';
COMMENT ON COLUMN ad_billing_ledger.slot IS '冻结最终展示位置';
COMMENT ON COLUMN ad_billing_ledger.traffic_class IS '服务端固定 SANDBOX 来源';
COMMENT ON COLUMN ad_billing_ledger.currency IS '固定人民币 CNY';
COMMENT ON COLUMN ad_billing_ledger.budget_date IS '被捕获预占原业务日';
COMMENT ON COLUMN ad_billing_ledger.amount IS '精确 CPM 单次收费金额元';
COMMENT ON COLUMN ad_billing_ledger.account_version IS '捕获提交对应账户版本，仅供关联，不用于丢弃旧事实';
COMMENT ON COLUMN ad_billing_ledger.accepted_at IS '持锁后校验未到期的服务端接受时间';
CREATE INDEX ad_billing_ledger_account_idx ON ad_billing_ledger(advertiser_id, budget_date, accepted_at, ledger_id);
ALTER TABLE ad_impression_receipt ADD CONSTRAINT ad_impression_receipt_ledger_fk FOREIGN KEY (ledger_id) REFERENCES ad_billing_ledger(ledger_id);
CREATE FUNCTION ad_billing_ledger_immutable() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''billing ledger is immutable''; END;';
CREATE TRIGGER ad_billing_ledger_no_update_delete BEFORE UPDATE OR DELETE ON ad_billing_ledger FOR EACH ROW EXECUTE FUNCTION ad_billing_ledger_immutable();

CREATE TABLE ad_billing_outbox (
    event_id UUID PRIMARY KEY REFERENCES ad_billing_ledger(ledger_id),
    event_type VARCHAR(64) NOT NULL CHECK (event_type='AdImpressionCapturedV1'),
    schema_version INTEGER NOT NULL CHECK (schema_version=1),
    destination VARCHAR(128) NOT NULL,
    partition_key VARCHAR(128) NOT NULL,
    payload_json TEXT NOT NULL CHECK (octet_length(payload_json)<=16384),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    published_at TIMESTAMPTZ
);
COMMENT ON TABLE ad_billing_outbox IS '与捕获账本同事务的待发事实；发布确认后才能设置 published_at';
COMMENT ON COLUMN ad_billing_outbox.event_id IS '稳定账本事件标识，消费者必须逐事件去重';
COMMENT ON COLUMN ad_billing_outbox.event_type IS '版本化事件类型';
COMMENT ON COLUMN ad_billing_outbox.schema_version IS '载荷 schema 主版本';
COMMENT ON COLUMN ad_billing_outbox.destination IS '冻结目标 topic，避免恢复时配置变更丢失目的地';
COMMENT ON COLUMN ad_billing_outbox.partition_key IS '广告主加原预算日，明确单分区顺序范围';
COMMENT ON COLUMN ad_billing_outbox.payload_json IS '版本化账本事实，不含 token 或认证主体';
COMMENT ON COLUMN ad_billing_outbox.created_at IS '本地业务事务中记录的事件时间';
COMMENT ON COLUMN ad_billing_outbox.published_at IS 'broker 确认并持久化时间；为空表示未确认';
CREATE INDEX ad_billing_outbox_pending_idx ON ad_billing_outbox(created_at, event_id) WHERE published_at IS NULL;
COMMIT;
