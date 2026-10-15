-- ER-S02A：显式应用到已完成 V001 的隔离 adDb，禁止覆盖历史迁移。
BEGIN;
INSERT INTO ad_billing_schema_history (version, description, script_sha256)
VALUES (2, '不可变 sandbox CPM 精确报价', current_setting('recsys.migration_sha256'));

CREATE TABLE ad_cpm_quote (
    traffic_class varchar(16) NOT NULL CHECK (traffic_class = 'SANDBOX'),
    quote_id uuid PRIMARY KEY,
    delivery_id uuid NOT NULL,
    advertiser_id bigint NOT NULL CHECK (advertiser_id > 0),
    ad_id bigint NOT NULL CHECK (ad_id > 0),
    slot integer NOT NULL CHECK (slot BETWEEN 1 AND 100),
    currency varchar(3) NOT NULL CHECK (currency = 'CNY'),
    price_unit varchar(32) NOT NULL CHECK (price_unit = 'PER_IMPRESSION'),
    pricing_version varchar(32) NOT NULL CHECK (pricing_version = 'CPM_GSP_CNY6_V1'),
    amount numeric(20,6) NOT NULL CHECK (amount BETWEEN 0 AND 99999999999999.999999),
    threshold varchar(800) NOT NULL CHECK (threshold ~ '^[0-9]+(\.[0-9]+)?$'),
    bill_factor varchar(800) NOT NULL CHECK (bill_factor ~ '^[0-9]+(\.[0-9]+)?$' AND bill_factor::numeric > 0),
    increment_amount numeric(20,6) NOT NULL CHECK (increment_amount BETWEEN 0 AND 99999999999999.999999),
    floor_amount numeric(20,6) NOT NULL CHECK (floor_amount BETWEEN 0 AND 99999999999999.999999),
    cap_amount numeric(20,6) NOT NULL CHECK (cap_amount BETWEEN 0 AND 99999999999999.999999),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    issued_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    UNIQUE (traffic_class, delivery_id, slot),
    CHECK (floor_amount <= amount AND amount <= cap_amount),
    CHECK (expires_at = issued_at + interval '5 minutes')
);
COMMENT ON TABLE ad_cpm_quote IS 'sandbox CPM 账务不可变报价，预占与结算只引用此处精确金额';
COMMENT ON COLUMN ad_cpm_quote.traffic_class IS '服务端固定 sandbox 来源';
COMMENT ON COLUMN ad_cpm_quote.quote_id IS '稳定报价标识';
COMMENT ON COLUMN ad_cpm_quote.delivery_id IS '整次交付操作标识';
COMMENT ON COLUMN ad_cpm_quote.advertiser_id IS '报价所属广告主';
COMMENT ON COLUMN ad_cpm_quote.ad_id IS '报价对应广告';
COMMENT ON COLUMN ad_cpm_quote.slot IS '冻结交付位置，首版最多一百位';
COMMENT ON COLUMN ad_cpm_quote.currency IS '固定人民币';
COMMENT ON COLUMN ad_cpm_quote.price_unit IS '元每单次曝光，不能从 CPM 名称猜千次价';
COMMENT ON COLUMN ad_cpm_quote.pricing_version IS '不可变计价算法版本';
COMMENT ON COLUMN ad_cpm_quote.amount IS 'HALF_UP 后单次精确报价，元';
COMMENT ON COLUMN ad_cpm_quote.threshold IS '次位排序阈值的十进制快照，不按金额精度截断';
COMMENT ON COLUMN ad_cpm_quote.bill_factor IS '自身计价因子十进制快照';
COMMENT ON COLUMN ad_cpm_quote.increment_amount IS '六位增量金额';
COMMENT ON COLUMN ad_cpm_quote.floor_amount IS '六位保留价';
COMMENT ON COLUMN ad_cpm_quote.cap_amount IS '十进制出价乘 pacing 后的六位上限';
COMMENT ON COLUMN ad_cpm_quote.payload_hash IS '完整规范化报价语义摘要';
COMMENT ON COLUMN ad_cpm_quote.issued_at IS '数据库时钟签发时间';
COMMENT ON COLUMN ad_cpm_quote.expires_at IS '首次签发五分钟到期，重试不得延期';
COMMIT;
