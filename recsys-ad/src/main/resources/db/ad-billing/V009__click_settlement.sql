BEGIN;
CREATE TABLE ad_click_settlement (
    qualification_id UUID PRIMARY KEY REFERENCES ad_click_quote(quote_id),
    budget_date DATE NOT NULL,
    decision VARCHAR(32) NOT NULL CHECK (decision IN ('HELD','INSUFFICIENT_BUDGET','ACCOUNT_MISSING','QUOTE_EXPIRED')),
    qualification_token VARCHAR(16384),
    prepared_at TIMESTAMPTZ NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('HELD','CAPTURED','RELEASED','EXPIRED','REJECTED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version>=0),
    visible_receipt_id UUID UNIQUE,
    click_receipt_id UUID UNIQUE,
    ledger_id UUID UNIQUE,
    closed_at TIMESTAMPTZ,
    close_reason VARCHAR(32),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((decision='HELD' AND qualification_token IS NOT NULL AND state<>'REJECTED')
        OR (decision<>'HELD' AND qualification_token IS NULL AND state='REJECTED')),
    CHECK ((state IN ('HELD','REJECTED') AND closed_at IS NULL AND close_reason IS NULL)
        OR (state='CAPTURED' AND closed_at IS NOT NULL AND close_reason IS NOT NULL AND close_reason='CLICK_CAPTURED')
        OR (state='EXPIRED' AND closed_at IS NOT NULL AND close_reason IS NOT NULL AND close_reason='EXPIRED')
        OR (state='RELEASED' AND closed_at IS NOT NULL AND close_reason IS NOT NULL AND close_reason IN ('DELIVERY_ABORTED','FRAUD_REJECTED'))),
    CHECK ((state='CAPTURED')=(ledger_id IS NOT NULL)),
    CHECK ((visible_receipt_id IS NOT NULL AND click_receipt_id IS NOT NULL)=(state='CAPTURED')),
    CHECK (visible_receipt_id IS NULL OR click_receipt_id IS NULL OR visible_receipt_id<>click_receipt_id),
    CHECK (state<>'REJECTED' OR (visible_receipt_id IS NULL AND click_receipt_id IS NULL))
);
COMMENT ON TABLE ad_click_settlement IS 'sandbox点击一报价一资格；原准备结果与当前生命周期分开，共享原预算账户';
COMMENT ON COLUMN ad_click_settlement.qualification_id IS '等于不可变点击quoteId，杜绝重复预占同一义务';
COMMENT ON COLUMN ad_click_settlement.budget_date IS '预占开始的上海预算日，跨日不转移义务';
COMMENT ON COLUMN ad_click_settlement.decision IS '不可变首次准备决定，补预算不能改变原拒绝';
COMMENT ON COLUMN ad_click_settlement.qualification_token IS '用途隔离的原签名资格，只有HELD有值，禁止日志输出';
COMMENT ON COLUMN ad_click_settlement.prepared_at IS '首次准备数据库时间';
COMMENT ON COLUMN ad_click_settlement.state IS '当前资金状态；两条件同时满足必须已经捕获';
COMMENT ON COLUMN ad_click_settlement.version IS '每次状态变化递增，用于条件更新';
COMMENT ON COLUMN ad_click_settlement.visible_receipt_id IS '最初被接受的可见回执';
COMMENT ON COLUMN ad_click_settlement.click_receipt_id IS '最初被接受且通过反作弊的点击回执';
COMMENT ON COLUMN ad_click_settlement.ledger_id IS '唯一捕获事实，等待或释放为空';
COMMENT ON COLUMN ad_click_settlement.closed_at IS '终态数据库时间';
COMMENT ON COLUMN ad_click_settlement.close_reason IS '终态原因，区分主动释放、超时、风控和收费';
COMMENT ON COLUMN ad_click_settlement.updated_at IS '最近一次持久化状态变化时间';
CREATE INDEX ad_click_settlement_held_idx ON ad_click_settlement(qualification_id) WHERE state='HELD';
CREATE INDEX ad_click_quote_expiry_idx ON ad_click_quote(expires_at,quote_id);

CREATE TABLE ad_click_receipt (
    receipt_id UUID PRIMARY KEY,
    qualification_id UUID NOT NULL REFERENCES ad_click_settlement(qualification_id),
    subject_key VARCHAR(200) NOT NULL CHECK (length(btrim(subject_key))>0),
    payload_hash CHAR(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('VISIBLE','CLICK')),
    visible_duration_ms BIGINT NOT NULL CHECK (visible_duration_ms BETWEEN 0 AND 300000),
    min_visible_ratio_bps INTEGER NOT NULL CHECK (min_visible_ratio_bps BETWEEN 0 AND 10000),
    page_visible BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACCEPTED_WAITING','ACCEPTED_CAPTURED','ALREADY_ACCEPTED','POLICY_NOT_MET','FRAUD_REJECTED','EXPIRED','NOT_HELD')),
    canonical_receipt_id UUID REFERENCES ad_click_receipt(receipt_id),
    accepted_at TIMESTAMPTZ,
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(receipt_id,qualification_id),
    CHECK (kind<>'CLICK' OR (visible_duration_ms=0 AND min_visible_ratio_bps=0 AND NOT page_visible)),
    CHECK ((status='PENDING')=(decided_at IS NULL)),
    CHECK ((status IN ('ACCEPTED_WAITING','ACCEPTED_CAPTURED','ALREADY_ACCEPTED') AND canonical_receipt_id IS NOT NULL AND accepted_at IS NOT NULL)
        OR (status IN ('PENDING','POLICY_NOT_MET','FRAUD_REJECTED','EXPIRED','NOT_HELD') AND canonical_receipt_id IS NULL AND accepted_at IS NULL)),
    CHECK (status NOT IN ('ACCEPTED_WAITING','ACCEPTED_CAPTURED') OR canonical_receipt_id=receipt_id)
);
COMMENT ON TABLE ad_click_receipt IS '点击链两种回执的不可变首次决定；PENDING仅限未提交事务，不存原token';
COMMENT ON COLUMN ad_click_receipt.receipt_id IS '跨两种回执共享命名空间的稳定命令身份';
COMMENT ON COLUMN ad_click_receipt.qualification_id IS '原点击资格，不能引用CPM资格';
COMMENT ON COLUMN ad_click_receipt.subject_key IS '认证主体，用于原结果访问及审计';
COMMENT ON COLUMN ad_click_receipt.payload_hash IS '含主体、token、种类、观测的长度前缀SHA256';
COMMENT ON COLUMN ad_click_receipt.kind IS '可见依赖VISIBLE或实际点击CLICK';
COMMENT ON COLUMN ad_click_receipt.visible_duration_ms IS '可见连续毫秒，点击固定零';
COMMENT ON COLUMN ad_click_receipt.min_visible_ratio_bps IS '最小可见面积基点，点击固定零';
COMMENT ON COLUMN ad_click_receipt.page_visible IS '连续观测时页面可见，点击固定false';
COMMENT ON COLUMN ad_click_receipt.status IS '原决定；WAITING不是已收费，查询同时带当前结算';
COMMENT ON COLUMN ad_click_receipt.canonical_receipt_id IS '同资格同种类的第一次接受回执';
COMMENT ON COLUMN ad_click_receipt.accepted_at IS '原条件接受的服务端数据库时刻';
COMMENT ON COLUMN ad_click_receipt.decided_at IS '本命令首次决策的数据库时刻';
COMMENT ON COLUMN ad_click_receipt.created_at IS '本事务领取命令时刻';
CREATE INDEX ad_click_receipt_qualification_idx ON ad_click_receipt(qualification_id);
ALTER TABLE ad_click_settlement ADD CONSTRAINT ad_click_visible_fk FOREIGN KEY(visible_receipt_id,qualification_id) REFERENCES ad_click_receipt(receipt_id,qualification_id);
ALTER TABLE ad_click_settlement ADD CONSTRAINT ad_click_click_fk FOREIGN KEY(click_receipt_id,qualification_id) REFERENCES ad_click_receipt(receipt_id,qualification_id);

CREATE TABLE ad_click_rate (
    user_id BIGINT PRIMARY KEY CHECK(user_id>0),
    minute_start TIMESTAMPTZ NOT NULL CHECK (minute_start=date_trunc('minute',minute_start)),
    accepted_count BIGINT NOT NULL CHECK(accepted_count>=0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE ad_click_rate IS 'sandbox每模拟用户固定分钟有效点击计数；与首次点击决定同事务，无Redis失效放行';
COMMENT ON COLUMN ad_click_rate.user_id IS '冻结报价中的sandbox用户，全广告主共享限额';
COMMENT ON COLUMN ad_click_rate.minute_start IS '持有全部必要锁后DB决策时刻所属分钟';
COMMENT ON COLUMN ad_click_rate.accepted_count IS '该分钟接受的不同资格点击数，不含重试和拒绝';
COMMENT ON COLUMN ad_click_rate.updated_at IS '最近一次成功计数的DB时刻';

CREATE TABLE ad_click_ledger (
    ledger_id UUID PRIMARY KEY,
    qualification_id UUID NOT NULL UNIQUE REFERENCES ad_click_settlement(qualification_id),
    visible_receipt_id UUID NOT NULL UNIQUE,
    click_receipt_id UUID NOT NULL UNIQUE,
    operation_id UUID NOT NULL,
    advertiser_id BIGINT NOT NULL CHECK(advertiser_id>0),
    ad_id BIGINT NOT NULL CHECK(ad_id>0),
    creative_id BIGINT NOT NULL CHECK(creative_id>=0),
    item_id BIGINT NOT NULL CHECK(item_id>0),
    user_id BIGINT NOT NULL CHECK(user_id>0),
    slot INTEGER NOT NULL CHECK(slot BETWEEN 1 AND 10),
    bid_type VARCHAR(8) NOT NULL CHECK(bid_type IN ('CPC','OCPC')),
    traffic_class VARCHAR(16) NOT NULL DEFAULT 'SANDBOX' CHECK(traffic_class='SANDBOX'),
    currency CHAR(3) NOT NULL DEFAULT 'CNY' CHECK(currency='CNY'),
    price_unit VARCHAR(32) NOT NULL DEFAULT 'PER_CLICK' CHECK(price_unit='PER_CLICK'),
    pricing_version VARCHAR(32) NOT NULL DEFAULT 'CLICK_GSP_CNY6_V1' CHECK(pricing_version='CLICK_GSP_CNY6_V1'),
    budget_date DATE NOT NULL,
    amount NUMERIC(20,6) NOT NULL CHECK(amount BETWEEN 0 AND 99999999999999.999999),
    account_version BIGINT NOT NULL CHECK(account_version>0),
    accepted_at TIMESTAMPTZ NOT NULL,
    CHECK(visible_receipt_id<>click_receipt_id),
    FOREIGN KEY(visible_receipt_id,qualification_id) REFERENCES ad_click_receipt(receipt_id,qualification_id),
    FOREIGN KEY(click_receipt_id,qualification_id) REFERENCES ad_click_receipt(receipt_id,qualification_id)
);
COMMENT ON TABLE ad_click_ledger IS '不可变点击资金事实，两条件同时满足后一资格最多一笔';
COMMENT ON COLUMN ad_click_ledger.ledger_id IS '唯一账本身份，也是Outbox事件ID';
COMMENT ON COLUMN ad_click_ledger.qualification_id IS '被捕获的点击资格';
COMMENT ON COLUMN ad_click_ledger.visible_receipt_id IS '最初可见回执，可能早于点击';
COMMENT ON COLUMN ad_click_ledger.click_receipt_id IS '最初有效点击回执，可能早于可见曝光';
COMMENT ON COLUMN ad_click_ledger.operation_id IS '冻结最终交付操作';
COMMENT ON COLUMN ad_click_ledger.advertiser_id IS '原预算账户所有者';
COMMENT ON COLUMN ad_click_ledger.ad_id IS '冻结广告';
COMMENT ON COLUMN ad_click_ledger.creative_id IS '冻结创意，零为默认创意';
COMMENT ON COLUMN ad_click_ledger.item_id IS '冻结内容标识';
COMMENT ON COLUMN ad_click_ledger.user_id IS '冻结sandbox模拟用户';
COMMENT ON COLUMN ad_click_ledger.slot IS '冻结最终位置';
COMMENT ON COLUMN ad_click_ledger.bid_type IS 'CPC或OCPC，不作为曝光收费';
COMMENT ON COLUMN ad_click_ledger.traffic_class IS '固定SANDBOX';
COMMENT ON COLUMN ad_click_ledger.currency IS '人民币';
COMMENT ON COLUMN ad_click_ledger.price_unit IS '元每单次点击';
COMMENT ON COLUMN ad_click_ledger.pricing_version IS '精确点击GSP定价版本';
COMMENT ON COLUMN ad_click_ledger.budget_date IS '原预占日，非当前日';
COMMENT ON COLUMN ad_click_ledger.amount IS '六位人民币精确金额，可为合法零元';
COMMENT ON COLUMN ad_click_ledger.account_version IS '捕获后的账户版本，不能用于舍弃乱序增量';
COMMENT ON COLUMN ad_click_ledger.accepted_at IS '第二条件持锁核验到期前的DB接受时刻';
CREATE INDEX ad_click_ledger_account_idx ON ad_click_ledger(advertiser_id,budget_date,accepted_at,ledger_id);
ALTER TABLE ad_click_settlement ADD CONSTRAINT ad_click_settlement_ledger_fk FOREIGN KEY(ledger_id) REFERENCES ad_click_ledger(ledger_id);

CREATE TABLE ad_click_outbox (
    event_id UUID PRIMARY KEY REFERENCES ad_click_ledger(ledger_id),
    event_type VARCHAR(64) NOT NULL CHECK(event_type='AdClickCapturedV1'),
    schema_version INTEGER NOT NULL CHECK(schema_version=1),
    destination VARCHAR(128) NOT NULL,
    partition_key VARCHAR(128) NOT NULL,
    payload_json TEXT NOT NULL CHECK(octet_length(payload_json)<=16384),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    published_at TIMESTAMPTZ,
    delivery_state VARCHAR(16) NOT NULL DEFAULT 'READY' CHECK(delivery_state IN ('READY','SENDING','PUBLISHED','DEAD')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 20),
    available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    fence BIGINT NOT NULL DEFAULT 0 CHECK(fence>=0),
    last_error VARCHAR(80),
    CHECK ((delivery_state='PUBLISHED')=(published_at IS NOT NULL))
);
COMMENT ON TABLE ad_click_outbox IS '点击捕获同事务Outbox；原始载荷不可变，实际可靠发布由S11C接入';
COMMENT ON COLUMN ad_click_outbox.event_id IS '稳定账本事件ID，至少一次消费须逐事件去重';
COMMENT ON COLUMN ad_click_outbox.event_type IS '独立版本化点击事件类型';
COMMENT ON COLUMN ad_click_outbox.schema_version IS '载荷主版本';
COMMENT ON COLUMN ad_click_outbox.destination IS '冻结目标topic';
COMMENT ON COLUMN ad_click_outbox.partition_key IS '广告主加原预算日';
COMMENT ON COLUMN ad_click_outbox.payload_json IS '无token和认证主体的精确点击事实';
COMMENT ON COLUMN ad_click_outbox.created_at IS '原事务创建时间';
COMMENT ON COLUMN ad_click_outbox.published_at IS 'broker确认后的持久化时间';
COMMENT ON COLUMN ad_click_outbox.delivery_state IS '可靠投递生命周期，耗尽保留原记录';
COMMENT ON COLUMN ad_click_outbox.attempts IS '领取次数含崩溃，最多二十次';
COMMENT ON COLUMN ad_click_outbox.available_at IS '下次重试时间或发送租约期限';
COMMENT ON COLUMN ad_click_outbox.fence IS '阻止旧执行器回写的单调令牌';
COMMENT ON COLUMN ad_click_outbox.last_error IS '受限脱敏错误类型';
CREATE INDEX ad_click_outbox_ready_idx ON ad_click_outbox(available_at,event_id) WHERE delivery_state IN ('READY','SENDING');
CREATE TRIGGER ad_click_ledger_immutable BEFORE UPDATE OR DELETE ON ad_click_ledger FOR EACH ROW EXECUTE FUNCTION ad_billing_ledger_immutable();
CREATE TRIGGER ad_click_outbox_payload_guard BEFORE UPDATE OR DELETE ON ad_click_outbox FOR EACH ROW EXECUTE FUNCTION ad_billing_outbox_payload_immutable();

CREATE FUNCTION ad_click_settlement_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'click settlement retention is governed'; END IF;
    IF (NEW.qualification_id,NEW.budget_date,NEW.decision,NEW.qualification_token,NEW.prepared_at)
       IS DISTINCT FROM (OLD.qualification_id,OLD.budget_date,OLD.decision,OLD.qualification_token,OLD.prepared_at)
       OR OLD.state<>'HELD' OR NEW.version<>OLD.version+1
       OR (OLD.visible_receipt_id IS NOT NULL AND NEW.visible_receipt_id IS DISTINCT FROM OLD.visible_receipt_id)
       OR (OLD.click_receipt_id IS NOT NULL AND NEW.click_receipt_id IS DISTINCT FROM OLD.click_receipt_id)
    THEN RAISE EXCEPTION 'click settlement transition is invalid'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ad_click_settlement_guard BEFORE UPDATE OR DELETE ON ad_click_settlement FOR EACH ROW EXECUTE FUNCTION ad_click_settlement_guard();
CREATE FUNCTION ad_click_receipt_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'click receipt retention is governed'; END IF;
    IF OLD.status<>'PENDING' OR (NEW.receipt_id,NEW.qualification_id,NEW.subject_key,NEW.payload_hash,NEW.kind,
       NEW.visible_duration_ms,NEW.min_visible_ratio_bps,NEW.page_visible,NEW.created_at)
       IS DISTINCT FROM (OLD.receipt_id,OLD.qualification_id,OLD.subject_key,OLD.payload_hash,OLD.kind,
       OLD.visible_duration_ms,OLD.min_visible_ratio_bps,OLD.page_visible,OLD.created_at)
    THEN RAISE EXCEPTION 'click receipt decision is immutable'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ad_click_receipt_guard BEFORE UPDATE OR DELETE ON ad_click_receipt FOR EACH ROW EXECUTE FUNCTION ad_click_receipt_guard();
-- 延迟到提交检查，数据库拒绝遗留的PENDING命令，不靠正常代码路径的约定兜底。
CREATE FUNCTION ad_click_receipt_complete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM ad_click_receipt WHERE receipt_id=NEW.receipt_id AND status='PENDING')
    THEN RAISE EXCEPTION 'click receipt must be decided before commit'; END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER ad_click_receipt_complete AFTER INSERT OR UPDATE ON ad_click_receipt
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ad_click_receipt_complete();
INSERT INTO ad_billing_schema_history(version,description,script_sha256)
VALUES(9,'sandbox atomic click settlement',current_setting('recsys.migration_sha256'));
COMMIT;
