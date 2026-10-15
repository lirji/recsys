BEGIN;
CREATE TABLE ad_click_outbox_replay (
    replay_id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES ad_click_outbox(event_id),
    actor VARCHAR(200) NOT NULL CHECK (length(trim(actor)) > 0),
    reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0),
    previous_attempts INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE ad_click_outbox_replay IS '耗尽投递的人工重放审计，不修改账本或原事件';
COMMENT ON COLUMN ad_click_outbox_replay.replay_id IS '人工重放操作标识';
COMMENT ON COLUMN ad_click_outbox_replay.event_id IS '保持不变的原账本事件标识';
COMMENT ON COLUMN ad_click_outbox_replay.actor IS '受控操作者身份';
COMMENT ON COLUMN ad_click_outbox_replay.reason IS '恢复原因或关联工单';
COMMENT ON COLUMN ad_click_outbox_replay.previous_attempts IS '重放前已耗用领取次数';
COMMENT ON COLUMN ad_click_outbox_replay.created_at IS '数据库审计时间';

CREATE TRIGGER ad_click_outbox_replay_immutable BEFORE UPDATE OR DELETE ON ad_click_outbox_replay
    FOR EACH ROW EXECUTE FUNCTION ad_billing_ledger_immutable();

CREATE TABLE ad_click_projection_fact (
    event_id UUID PRIMARY KEY,
    qualification_id UUID NOT NULL UNIQUE,
    visible_receipt_id UUID NOT NULL UNIQUE,
    click_receipt_id UUID NOT NULL UNIQUE,
    advertiser_id BIGINT NOT NULL CHECK (advertiser_id>0),
    budget_date DATE NOT NULL,
    amount NUMERIC(20,6) NOT NULL CHECK (amount>=0),
    payload_hash CHAR(64) NOT NULL,
    payload_json TEXT NOT NULL CHECK (octet_length(payload_json)<=16384),
    applied_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE ad_click_projection_fact IS '按独立账本事实去重的 Inbox 与可重建明细，不是收费权威';
COMMENT ON COLUMN ad_click_projection_fact.event_id IS '稳定账本事件标识，不能用账户最大版本替代';
COMMENT ON COLUMN ad_click_projection_fact.qualification_id IS '原资格唯一业务键';
COMMENT ON COLUMN ad_click_projection_fact.visible_receipt_id IS '原可见回执唯一业务键';
COMMENT ON COLUMN ad_click_projection_fact.click_receipt_id IS '原点击回执唯一业务键';
COMMENT ON COLUMN ad_click_projection_fact.advertiser_id IS 'sandbox 广告主';
COMMENT ON COLUMN ad_click_projection_fact.budget_date IS '原预占预算日';
COMMENT ON COLUMN ad_click_projection_fact.amount IS '人民币六位小数逐笔事实金额';
COMMENT ON COLUMN ad_click_projection_fact.payload_hash IS '已知 v1 字段规范化 SHA256，检测同 ID 变负载';
COMMENT ON COLUMN ad_click_projection_fact.payload_json IS '已知 v1 字段规范化原事实，用于审计重建';
COMMENT ON COLUMN ad_click_projection_fact.applied_at IS '数据库首次应用时间';
CREATE INDEX idx_ad_click_projection_account ON ad_click_projection_fact(advertiser_id,budget_date,event_id);
CREATE TRIGGER ad_click_projection_fact_immutable BEFORE UPDATE OR DELETE ON ad_click_projection_fact
    FOR EACH ROW EXECUTE FUNCTION ad_billing_ledger_immutable();

CREATE TABLE ad_click_projection_receipt (
    receipt_id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES ad_click_projection_fact(event_id),
    kind VARCHAR(16) NOT NULL CHECK(kind IN ('VISIBLE','CLICK')),
    UNIQUE(event_id,kind)
);
COMMENT ON TABLE ad_click_projection_receipt IS 'Inbox回执身份索引，防止同一receipt跨种类或跨事件重用';
COMMENT ON COLUMN ad_click_projection_receipt.receipt_id IS '两种回执共享的唯一命令身份';
COMMENT ON COLUMN ad_click_projection_receipt.event_id IS '唯一所属的点击资金事实';
COMMENT ON COLUMN ad_click_projection_receipt.kind IS '被冻结的可见或点击用途，不允许互换';
CREATE TRIGGER ad_click_projection_receipt_immutable BEFORE UPDATE OR DELETE ON ad_click_projection_receipt
    FOR EACH ROW EXECUTE FUNCTION ad_billing_ledger_immutable();

CREATE TABLE ad_click_projection_daily (
    advertiser_id BIGINT NOT NULL CHECK (advertiser_id>0),
    budget_date DATE NOT NULL,
    traffic_class VARCHAR(16) NOT NULL DEFAULT 'SANDBOX' CHECK (traffic_class='SANDBOX'),
    currency CHAR(3) NOT NULL DEFAULT 'CNY' CHECK (currency='CNY'),
    click_count BIGINT NOT NULL CHECK (click_count>0),
    charged_amount NUMERIC(26,6) NOT NULL CHECK (charged_amount>=0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (advertiser_id,budget_date)
);
COMMENT ON TABLE ad_click_projection_daily IS 'sandbox 点击资金日投影，只能由独立事实累加，不参与余额校验';
COMMENT ON COLUMN ad_click_projection_daily.advertiser_id IS 'sandbox 广告主';
COMMENT ON COLUMN ad_click_projection_daily.budget_date IS '原预占预算日';
COMMENT ON COLUMN ad_click_projection_daily.traffic_class IS '固定 sandbox，禁止与正式收费混用';
COMMENT ON COLUMN ad_click_projection_daily.currency IS '人民币';
COMMENT ON COLUMN ad_click_projection_daily.click_count IS '不同账本事件累计点击数';
COMMENT ON COLUMN ad_click_projection_daily.charged_amount IS '不同账本事件精确累计金额';
COMMENT ON COLUMN ad_click_projection_daily.updated_at IS '数据库最近投影时间';
INSERT INTO ad_billing_schema_history(version,description,script_sha256)
VALUES(10,'sandbox reliable click events',current_setting('recsys.migration_sha256'));
COMMIT;
