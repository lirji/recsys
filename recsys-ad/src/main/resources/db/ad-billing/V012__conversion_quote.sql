BEGIN;
CREATE TABLE ad_conversion_quote (
    quote_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL,
    subject_key VARCHAR(200) NOT NULL CHECK (length(btrim(subject_key))>0),
    user_id BIGINT NOT NULL CHECK (user_id>0),
    advertiser_id BIGINT NOT NULL CHECK (advertiser_id>0),
    ad_id BIGINT NOT NULL CHECK (ad_id>0),
    creative_id BIGINT NOT NULL CHECK (creative_id>=0),
    item_id BIGINT NOT NULL CHECK (item_id>0),
    slot INTEGER NOT NULL CHECK (slot BETWEEN 1 AND 10),
    title VARCHAR(2000) NOT NULL CHECK (length(btrim(title))>0),
    traffic_class VARCHAR(16) NOT NULL DEFAULT 'SANDBOX' CHECK (traffic_class='SANDBOX'),
    bid_type VARCHAR(8) NOT NULL CHECK (bid_type='CPA'),
    currency CHAR(3) NOT NULL DEFAULT 'CNY' CHECK (currency='CNY'),
    price_unit VARCHAR(32) NOT NULL DEFAULT 'PER_CONVERSION' CHECK (price_unit='PER_CONVERSION'),
    pricing_version VARCHAR(32) NOT NULL DEFAULT 'CPA_GSP_CNY6_V1' CHECK (pricing_version='CPA_GSP_CNY6_V1'),
    amount NUMERIC(20,6) NOT NULL CHECK (amount BETWEEN 0 AND 99999999999999.999999),
    threshold VARCHAR(800) NOT NULL CHECK (threshold ~ '^[0-9]+(\.[0-9]+)?$'),
    bill_factor VARCHAR(800) NOT NULL CHECK (bill_factor ~ '^[0-9]+(\.[0-9]+)?$' AND bill_factor::numeric>0),
    increment_amount NUMERIC(20,6) NOT NULL CHECK (increment_amount BETWEEN 0 AND 99999999999999.999999),
    floor_amount NUMERIC(20,6) NOT NULL CHECK (floor_amount BETWEEN 0 AND 99999999999999.999999),
    cap_amount NUMERIC(20,6) NOT NULL CHECK (cap_amount BETWEEN 0 AND 99999999999999.999999),
    payload_hash CHAR(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE (operation_id,slot),
    CHECK (floor_amount<=amount AND amount<=cap_amount),
    CHECK (expires_at=issued_at+interval '5 minutes')
);
COMMENT ON TABLE ad_conversion_quote IS 'sandbox CPA不可变报价；一资格最大义务为完整单次转化价';
COMMENT ON COLUMN ad_conversion_quote.quote_id IS '稳定报价身份，后续资格引用';
COMMENT ON COLUMN ad_conversion_quote.operation_id IS '服务端最终交付操作身份';
COMMENT ON COLUMN ad_conversion_quote.subject_key IS '原认证主体，不接受客户端自选';
COMMENT ON COLUMN ad_conversion_quote.user_id IS '原模拟用户，不用后续表单值替换';
COMMENT ON COLUMN ad_conversion_quote.advertiser_id IS '广告主预算所有者';
COMMENT ON COLUMN ad_conversion_quote.ad_id IS '原竞得广告';
COMMENT ON COLUMN ad_conversion_quote.creative_id IS '最终DCO创意身份';
COMMENT ON COLUMN ad_conversion_quote.item_id IS '最终内容身份';
COMMENT ON COLUMN ad_conversion_quote.slot IS '最终位置，重试不可变';
COMMENT ON COLUMN ad_conversion_quote.title IS '原展示标题，绑定原资格';
COMMENT ON COLUMN ad_conversion_quote.traffic_class IS '固定服务端sandbox来源';
COMMENT ON COLUMN ad_conversion_quote.bid_type IS 'CPA，由经认证转化触发收费';
COMMENT ON COLUMN ad_conversion_quote.currency IS '人民币';
COMMENT ON COLUMN ad_conversion_quote.price_unit IS '元每单次转化，不能当曝光价';
COMMENT ON COLUMN ad_conversion_quote.pricing_version IS '精确标准GSP转化计价版本';
COMMENT ON COLUMN ad_conversion_quote.amount IS '六位HALF_UP后完整转化义务，不按概率折算';
COMMENT ON COLUMN ad_conversion_quote.threshold IS '次位排序阈值的十进制快照';
COMMENT ON COLUMN ad_conversion_quote.bill_factor IS '自身未含探索加成的计价因子';
COMMENT ON COLUMN ad_conversion_quote.increment_amount IS '六位价格增量';
COMMENT ON COLUMN ad_conversion_quote.floor_amount IS '六位底价';
COMMENT ON COLUMN ad_conversion_quote.cap_amount IS '有效出价乘pacing的六位上限';
COMMENT ON COLUMN ad_conversion_quote.payload_hash IS '长度前缀规范化完整上下文SHA256';
COMMENT ON COLUMN ad_conversion_quote.issued_at IS '数据库首次签发时刻';
COMMENT ON COLUMN ad_conversion_quote.expires_at IS '首次签发五分钟到期，重试不续期';
CREATE FUNCTION ad_conversion_quote_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '转化报价历史不可修改或删除';
END;
$$;
CREATE TRIGGER ad_conversion_quote_guard BEFORE UPDATE OR DELETE ON ad_conversion_quote
    FOR EACH ROW EXECUTE FUNCTION ad_conversion_quote_immutable();
INSERT INTO ad_billing_schema_history(version,description,script_sha256)
VALUES (12,'sandbox immutable CPA quote',current_setting('recsys.migration_sha256'));
COMMIT;
