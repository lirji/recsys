-- ER-S03：V001/V002 后显式应用到隔离 adDb，保留既有账户与报价。
BEGIN;
INSERT INTO ad_billing_schema_history (version, description, script_sha256)
VALUES (3, 'sandbox CPM 预算预占与到期释放', current_setting('recsys.migration_sha256'));

CREATE TABLE ad_budget_reservation (
    traffic_class varchar(16) NOT NULL CHECK (traffic_class = 'SANDBOX'),
    reservation_id uuid PRIMARY KEY,
    quote_id uuid NOT NULL UNIQUE REFERENCES ad_cpm_quote(quote_id),
    advertiser_id bigint NOT NULL CHECK (advertiser_id > 0),
    ad_id bigint NOT NULL CHECK (ad_id > 0),
    subject_key varchar(200) NOT NULL CHECK (length(btrim(subject_key)) > 0),
    budget_date date NOT NULL,
    amount numeric(20,6) NOT NULL CHECK (amount BETWEEN 0 AND 99999999999999.999999),
    expires_at timestamptz NOT NULL,
    decision varchar(32) CHECK (decision IN ('HELD', 'INSUFFICIENT_BUDGET', 'QUOTE_EXPIRED', 'ACCOUNT_MISSING')),
    state varchar(16) NOT NULL CHECK (state IN ('PENDING', 'HELD', 'REJECTED', 'RELEASED', 'EXPIRED')),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    closed_at timestamptz,
    close_reason varchar(32) CHECK (close_reason IN ('DELIVERY_ABORTED', 'EXPIRED')),
    CHECK ((state = 'PENDING') = (decision IS NULL)),
    CHECK ((state = 'PENDING' AND decision IS NULL)
        OR (state IN ('HELD', 'RELEASED', 'EXPIRED') AND decision = 'HELD')
        OR (state = 'REJECTED' AND decision IN ('INSUFFICIENT_BUDGET', 'QUOTE_EXPIRED', 'ACCOUNT_MISSING'))),
    CHECK ((state IN ('PENDING', 'HELD', 'REJECTED') AND closed_at IS NULL AND close_reason IS NULL)
        OR (state = 'RELEASED' AND closed_at IS NOT NULL AND close_reason IS NOT NULL AND close_reason = 'DELIVERY_ABORTED')
        OR (state = 'EXPIRED' AND closed_at IS NOT NULL AND close_reason IS NOT NULL AND close_reason = 'EXPIRED'))
);
COMMENT ON TABLE ad_budget_reservation IS 'sandbox CPM 预占首次决策与当前状态；同库账户占用的恢复依据';
COMMENT ON COLUMN ad_budget_reservation.traffic_class IS '固定 sandbox 来源';
COMMENT ON COLUMN ad_budget_reservation.reservation_id IS '调用方稳定生成的预占命令标识';
COMMENT ON COLUMN ad_budget_reservation.quote_id IS '服务端冻结报价，一份报价最多一笔预占';
COMMENT ON COLUMN ad_budget_reservation.advertiser_id IS '从报价继承的广告主';
COMMENT ON COLUMN ad_budget_reservation.ad_id IS '从报价继承的广告';
COMMENT ON COLUMN ad_budget_reservation.subject_key IS '可信交付原始主体，查询和释放必须匹配';
COMMENT ON COLUMN ad_budget_reservation.budget_date IS '首次受理时 Asia/Shanghai 预算日，跨日不移动';
COMMENT ON COLUMN ad_budget_reservation.amount IS '冻结报价最大义务，元';
COMMENT ON COLUMN ad_budget_reservation.expires_at IS '继承报价到期时间，不接受客户端指定';
COMMENT ON COLUMN ad_budget_reservation.decision IS '不可变首次 prepare 结论';
COMMENT ON COLUMN ad_budget_reservation.state IS '当前状态；PENDING 仅在未提交事务内';
COMMENT ON COLUMN ad_budget_reservation.version IS '预占状态版本';
COMMENT ON COLUMN ad_budget_reservation.created_at IS '数据库时钟首次受理时间';
COMMENT ON COLUMN ad_budget_reservation.updated_at IS '最近状态变化时间';
COMMENT ON COLUMN ad_budget_reservation.closed_at IS '释放或过期完成时间';
COMMENT ON COLUMN ad_budget_reservation.close_reason IS '服务端释放原因，重复操作不覆盖';
CREATE INDEX ad_budget_reservation_due ON ad_budget_reservation(expires_at, reservation_id) WHERE state = 'HELD';
CREATE INDEX ad_budget_reservation_account ON ad_budget_reservation(advertiser_id, budget_date, state);
COMMIT;
