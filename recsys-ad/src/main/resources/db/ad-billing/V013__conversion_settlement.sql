BEGIN;
CREATE UNIQUE INDEX ad_conversion_quote_owner_idx ON ad_conversion_quote(quote_id,advertiser_id);
CREATE TABLE ad_conversion_settlement (
    qualification_id UUID PRIMARY KEY,
    advertiser_id BIGINT NOT NULL CHECK(advertiser_id>0),
    budget_date DATE NOT NULL,
    decision VARCHAR(32) NOT NULL CHECK (decision IN ('HELD','INSUFFICIENT_BUDGET','ACCOUNT_MISSING','QUOTE_EXPIRED')),
    qualification_token VARCHAR(16384),
    prepared_at TIMESTAMPTZ NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('HELD','CAPTURED','RELEASED','EXPIRED','REJECTED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version>=0),
    visible_receipt_id UUID UNIQUE,
    outcome_id UUID UNIQUE,
    ledger_id UUID UNIQUE,
    closed_at TIMESTAMPTZ,
    close_reason VARCHAR(32),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((decision='HELD' AND qualification_token IS NOT NULL AND state<>'REJECTED')
        OR (decision<>'HELD' AND qualification_token IS NULL AND state='REJECTED')),
    CHECK ((state IN ('HELD','REJECTED') AND closed_at IS NULL AND close_reason IS NULL)
        OR (state='CAPTURED' AND closed_at IS NOT NULL AND close_reason IS NOT NULL AND close_reason='CONVERSION_CAPTURED')
        OR (state='EXPIRED' AND closed_at IS NOT NULL AND close_reason IS NOT NULL AND close_reason='EXPIRED')
        OR (state='RELEASED' AND closed_at IS NOT NULL AND close_reason IS NOT NULL AND close_reason='DELIVERY_ABORTED')),
    CHECK ((state='CAPTURED')=(ledger_id IS NOT NULL)),
    CHECK ((visible_receipt_id IS NOT NULL AND outcome_id IS NOT NULL)=(state='CAPTURED')),
    UNIQUE(qualification_id,advertiser_id),
    FOREIGN KEY(qualification_id,advertiser_id) REFERENCES ad_conversion_quote(quote_id,advertiser_id),
    CHECK (state<>'REJECTED' OR (visible_receipt_id IS NULL AND outcome_id IS NULL))
);
COMMENT ON TABLE ad_conversion_settlement IS 'sandbox转化一报价一资格；原准备结果与当前生命周期分开，共享原预算账户';
COMMENT ON COLUMN ad_conversion_settlement.qualification_id IS '等于不可变转化quoteId，杜绝重复预占同一义务';
COMMENT ON COLUMN ad_conversion_settlement.budget_date IS '预占开始的上海预算日，跨日不转移义务';
COMMENT ON COLUMN ad_conversion_settlement.decision IS '不可变首次准备决定，补预算不能改变原拒绝';
COMMENT ON COLUMN ad_conversion_settlement.qualification_token IS '用途隔离的原签名资格，只有HELD有值，禁止日志输出';
COMMENT ON COLUMN ad_conversion_settlement.prepared_at IS '首次准备数据库时间';
COMMENT ON COLUMN ad_conversion_settlement.state IS '当前资金状态；两条件同时满足必须已经捕获';
COMMENT ON COLUMN ad_conversion_settlement.version IS '每次状态变化递增，用于条件更新';
COMMENT ON COLUMN ad_conversion_settlement.visible_receipt_id IS '最初被接受的可见回执';
COMMENT ON COLUMN ad_conversion_settlement.outcome_id IS '最初被接受的广告主业务转化事实';
COMMENT ON COLUMN ad_conversion_settlement.ledger_id IS '唯一捕获事实，等待或释放为空';
COMMENT ON COLUMN ad_conversion_settlement.closed_at IS '终态数据库时间';
COMMENT ON COLUMN ad_conversion_settlement.close_reason IS '终态原因，区分主动释放、超时、风控和收费';
COMMENT ON COLUMN ad_conversion_settlement.updated_at IS '最近一次持久化状态变化时间';
CREATE INDEX ad_conversion_settlement_held_idx ON ad_conversion_settlement(qualification_id) WHERE state='HELD';
CREATE INDEX ad_conversion_quote_expiry_idx ON ad_conversion_quote(expires_at,quote_id);

COMMENT ON COLUMN ad_conversion_settlement.advertiser_id IS '报价中的原预算所有者，与业务转化作用域绑定';
CREATE TABLE ad_conversion_visible (
    receipt_id UUID PRIMARY KEY,
    qualification_id UUID NOT NULL REFERENCES ad_conversion_settlement(qualification_id),
    subject_key VARCHAR(200) NOT NULL CHECK (length(btrim(subject_key))>0),
    payload_hash CHAR(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    visible_duration_ms BIGINT NOT NULL CHECK (visible_duration_ms BETWEEN 0 AND 300000),
    min_visible_ratio_bps INTEGER NOT NULL CHECK (min_visible_ratio_bps BETWEEN 0 AND 10000),
    page_visible BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACCEPTED_WAITING','ACCEPTED_CAPTURED','ALREADY_ACCEPTED','POLICY_NOT_MET','EXPIRED','NOT_HELD')),
    canonical_receipt_id UUID,
    accepted_at TIMESTAMPTZ,
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(receipt_id,qualification_id),
    FOREIGN KEY(canonical_receipt_id,qualification_id) REFERENCES ad_conversion_visible(receipt_id,qualification_id),
    CHECK ((status='PENDING')=(decided_at IS NULL)),
    CHECK ((status IN ('ACCEPTED_WAITING','ACCEPTED_CAPTURED','ALREADY_ACCEPTED') AND canonical_receipt_id IS NOT NULL AND accepted_at IS NOT NULL)
        OR (status IN ('PENDING','POLICY_NOT_MET','EXPIRED','NOT_HELD') AND canonical_receipt_id IS NULL AND accepted_at IS NULL)),
    CHECK (status NOT IN ('ACCEPTED_WAITING','ACCEPTED_CAPTURED') OR canonical_receipt_id=receipt_id)
);
COMMENT ON TABLE ad_conversion_visible IS 'CPA可见回执的不可变首次决定；PENDING仅限未提交事务，不存原token';
COMMENT ON COLUMN ad_conversion_visible.receipt_id IS '可见回执稳定命令身份';
COMMENT ON COLUMN ad_conversion_visible.qualification_id IS '原CPA资格，不能引用CPM资格';
COMMENT ON COLUMN ad_conversion_visible.subject_key IS '认证主体，用于原结果访问及审计';
COMMENT ON COLUMN ad_conversion_visible.payload_hash IS '含主体、token、观测的长度前缀SHA256';
COMMENT ON COLUMN ad_conversion_visible.visible_duration_ms IS '可见连续毫秒';
COMMENT ON COLUMN ad_conversion_visible.min_visible_ratio_bps IS '最小可见面积基点';
COMMENT ON COLUMN ad_conversion_visible.page_visible IS '连续观测时页面可见';
COMMENT ON COLUMN ad_conversion_visible.status IS '原决定；WAITING不是已收费，查询同时带当前结算';
COMMENT ON COLUMN ad_conversion_visible.canonical_receipt_id IS '同资格同种类的第一次接受回执';
COMMENT ON COLUMN ad_conversion_visible.accepted_at IS '原条件接受的服务端数据库时刻';
COMMENT ON COLUMN ad_conversion_visible.decided_at IS '本命令首次决策的数据库时刻';
COMMENT ON COLUMN ad_conversion_visible.created_at IS '本事务领取命令时刻';
CREATE INDEX ad_conversion_visible_qualification_idx ON ad_conversion_visible(qualification_id);
CREATE TABLE ad_conversion_outcome (
    outcome_id UUID PRIMARY KEY,
    advertiser_id BIGINT NOT NULL,
    event_id UUID NOT NULL,
    qualification_id UUID NOT NULL,
    actor VARCHAR(200) NOT NULL CHECK(length(btrim(actor))>0),
    payload_hash CHAR(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ACCEPTED_WAITING','ACCEPTED_CAPTURED','DUPLICATE_QUALIFICATION','OUTSIDE_ATTRIBUTION','EXPIRED','NOT_HELD')),
    canonical_outcome_id UUID,
    decided_at TIMESTAMPTZ,
    UNIQUE(advertiser_id,event_id),
    UNIQUE(outcome_id,qualification_id),
    FOREIGN KEY(qualification_id,advertiser_id) REFERENCES ad_conversion_settlement(qualification_id,advertiser_id),
    FOREIGN KEY(canonical_outcome_id,qualification_id) REFERENCES ad_conversion_outcome(outcome_id,qualification_id),
    CHECK((status='PENDING')=(decided_at IS NULL)),
    CHECK((status IN ('ACCEPTED_WAITING','ACCEPTED_CAPTURED') AND canonical_outcome_id=outcome_id AND canonical_outcome_id IS NOT NULL)
        OR (status='DUPLICATE_QUALIFICATION' AND canonical_outcome_id IS NOT NULL AND canonical_outcome_id<>outcome_id)
        OR (status IN ('PENDING','OUTSIDE_ATTRIBUTION','EXPIRED','NOT_HELD') AND canonical_outcome_id IS NULL))
);
COMMENT ON TABLE ad_conversion_outcome IS '广告主作用域业务转化事实及原决定；等待、迟到和重复均保留，不等价于收费';
COMMENT ON COLUMN ad_conversion_outcome.outcome_id IS '内部不可变事实身份，不使用跨广告主可能重复的eventId当主键';
COMMENT ON COLUMN ad_conversion_outcome.advertiser_id IS '经授权的广告主作用域，外键绑定原报价所有者';
COMMENT ON COLUMN ad_conversion_outcome.event_id IS '广告主提供的稳定UUID业务身份，同作用域不可重绑资格或发生时间';
COMMENT ON COLUMN ad_conversion_outcome.qualification_id IS '归因的原CPA资格';
COMMENT ON COLUMN ad_conversion_outcome.actor IS '最初经认证且已归属授权的回调主体，不作为幂等业务内容';
COMMENT ON COLUMN ad_conversion_outcome.payload_hash IS '广告主、业务ID、资格token及发生时间的长度前缀SHA256';
COMMENT ON COLUMN ad_conversion_outcome.occurred_at IS '广告主声明的UTC发生时刻，微秒精度，须在归因窗口内';
COMMENT ON COLUMN ad_conversion_outcome.received_at IS '首次领取业务键的数据库时间，不随重试刷新';
COMMENT ON COLUMN ad_conversion_outcome.status IS '首次决定不可变，查询同时带当前资金状态';
COMMENT ON COLUMN ad_conversion_outcome.canonical_outcome_id IS '被接受或重复引用的本资格最初有效转化';
COMMENT ON COLUMN ad_conversion_outcome.decided_at IS '全部必要锁持有后作出原决定的数据库时刻';
CREATE INDEX ad_conversion_outcome_qualification_idx ON ad_conversion_outcome(qualification_id);
ALTER TABLE ad_conversion_settlement ADD CONSTRAINT ad_conversion_visible_fk FOREIGN KEY(visible_receipt_id,qualification_id) REFERENCES ad_conversion_visible(receipt_id,qualification_id);
ALTER TABLE ad_conversion_settlement ADD CONSTRAINT ad_conversion_outcome_fk FOREIGN KEY(outcome_id,qualification_id) REFERENCES ad_conversion_outcome(outcome_id,qualification_id);
CREATE TABLE ad_conversion_ledger (
    ledger_id UUID PRIMARY KEY,
    qualification_id UUID NOT NULL UNIQUE REFERENCES ad_conversion_settlement(qualification_id),
    visible_receipt_id UUID NOT NULL UNIQUE,
    outcome_id UUID NOT NULL UNIQUE,
    operation_id UUID NOT NULL,
    advertiser_id BIGINT NOT NULL CHECK(advertiser_id>0),
    ad_id BIGINT NOT NULL CHECK(ad_id>0),
    creative_id BIGINT NOT NULL CHECK(creative_id>=0),
    item_id BIGINT NOT NULL CHECK(item_id>0),
    user_id BIGINT NOT NULL CHECK(user_id>0),
    slot INTEGER NOT NULL CHECK(slot BETWEEN 1 AND 10),
    bid_type VARCHAR(8) NOT NULL CHECK(bid_type='CPA'),
    traffic_class VARCHAR(16) NOT NULL DEFAULT 'SANDBOX' CHECK(traffic_class='SANDBOX'),
    currency CHAR(3) NOT NULL DEFAULT 'CNY' CHECK(currency='CNY'),
    price_unit VARCHAR(32) NOT NULL DEFAULT 'PER_CONVERSION' CHECK(price_unit='PER_CONVERSION'),
    pricing_version VARCHAR(32) NOT NULL DEFAULT 'CPA_GSP_CNY6_V1' CHECK(pricing_version='CPA_GSP_CNY6_V1'),
    budget_date DATE NOT NULL,
    amount NUMERIC(20,6) NOT NULL CHECK(amount BETWEEN 0 AND 99999999999999.999999),
    account_version BIGINT NOT NULL CHECK(account_version>0),
    accepted_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(qualification_id,advertiser_id) REFERENCES ad_conversion_settlement(qualification_id,advertiser_id),
    FOREIGN KEY(visible_receipt_id,qualification_id) REFERENCES ad_conversion_visible(receipt_id,qualification_id),
    FOREIGN KEY(outcome_id,qualification_id) REFERENCES ad_conversion_outcome(outcome_id,qualification_id)
);
COMMENT ON TABLE ad_conversion_ledger IS '不可变转化资金事实，两条件同时满足后一资格最多一笔';
COMMENT ON COLUMN ad_conversion_ledger.ledger_id IS '唯一账本身份，也是Outbox事件ID';
COMMENT ON COLUMN ad_conversion_ledger.qualification_id IS '被捕获的转化资格';
COMMENT ON COLUMN ad_conversion_ledger.visible_receipt_id IS '最初可见回执，可能早于转化';
COMMENT ON COLUMN ad_conversion_ledger.outcome_id IS '最初有效业务转化事实，可能早于可见曝光';
COMMENT ON COLUMN ad_conversion_ledger.operation_id IS '冻结最终交付操作';
COMMENT ON COLUMN ad_conversion_ledger.advertiser_id IS '原预算账户所有者';
COMMENT ON COLUMN ad_conversion_ledger.ad_id IS '冻结广告';
COMMENT ON COLUMN ad_conversion_ledger.creative_id IS '冻结创意，零为默认创意';
COMMENT ON COLUMN ad_conversion_ledger.item_id IS '冻结内容标识';
COMMENT ON COLUMN ad_conversion_ledger.user_id IS '冻结sandbox模拟用户';
COMMENT ON COLUMN ad_conversion_ledger.slot IS '冻结最终位置';
COMMENT ON COLUMN ad_conversion_ledger.bid_type IS 'CPA，不作为曝光或点击收费';
COMMENT ON COLUMN ad_conversion_ledger.traffic_class IS '固定SANDBOX';
COMMENT ON COLUMN ad_conversion_ledger.currency IS '人民币';
COMMENT ON COLUMN ad_conversion_ledger.price_unit IS '元每单次转化';
COMMENT ON COLUMN ad_conversion_ledger.pricing_version IS '精确转化GSP定价版本';
COMMENT ON COLUMN ad_conversion_ledger.budget_date IS '原预占日，非当前日';
COMMENT ON COLUMN ad_conversion_ledger.amount IS '六位人民币精确金额，可为合法零元';
COMMENT ON COLUMN ad_conversion_ledger.account_version IS '捕获后的账户版本，不能用于舍弃乱序增量';
COMMENT ON COLUMN ad_conversion_ledger.accepted_at IS '第二条件持锁核验到期前的DB接受时刻';
CREATE INDEX ad_conversion_ledger_account_idx ON ad_conversion_ledger(advertiser_id,budget_date,accepted_at,ledger_id);
ALTER TABLE ad_conversion_settlement ADD CONSTRAINT ad_conversion_settlement_ledger_fk FOREIGN KEY(ledger_id) REFERENCES ad_conversion_ledger(ledger_id);

CREATE TABLE ad_conversion_outbox (
    event_id UUID PRIMARY KEY REFERENCES ad_conversion_ledger(ledger_id),
    event_type VARCHAR(64) NOT NULL CHECK(event_type='AdConversionCapturedV1'),
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
COMMENT ON TABLE ad_conversion_outbox IS '转化捕获同事务Outbox；原始载荷不可变，实际可靠发布由S12C接入';
COMMENT ON COLUMN ad_conversion_outbox.event_id IS '稳定账本事件ID，至少一次消费须逐事件去重';
COMMENT ON COLUMN ad_conversion_outbox.event_type IS '独立版本化转化事件类型';
COMMENT ON COLUMN ad_conversion_outbox.schema_version IS '载荷主版本';
COMMENT ON COLUMN ad_conversion_outbox.destination IS '冻结目标topic';
COMMENT ON COLUMN ad_conversion_outbox.partition_key IS '广告主加原预算日';
COMMENT ON COLUMN ad_conversion_outbox.payload_json IS '无token和认证主体的精确转化事实';
COMMENT ON COLUMN ad_conversion_outbox.created_at IS '原事务创建时间';
COMMENT ON COLUMN ad_conversion_outbox.published_at IS 'broker确认后的持久化时间';
COMMENT ON COLUMN ad_conversion_outbox.delivery_state IS '可靠投递生命周期，耗尽保留原记录';
COMMENT ON COLUMN ad_conversion_outbox.attempts IS '领取次数含崩溃，最多二十次';
COMMENT ON COLUMN ad_conversion_outbox.available_at IS '下次重试时间或发送租约期限';
COMMENT ON COLUMN ad_conversion_outbox.fence IS '阻止旧执行器回写的单调令牌';
COMMENT ON COLUMN ad_conversion_outbox.last_error IS '受限脱敏错误类型';
CREATE INDEX ad_conversion_outbox_ready_idx ON ad_conversion_outbox(available_at,event_id) WHERE delivery_state IN ('READY','SENDING');
CREATE TRIGGER ad_conversion_ledger_immutable BEFORE UPDATE OR DELETE ON ad_conversion_ledger FOR EACH ROW EXECUTE FUNCTION ad_billing_ledger_immutable();
CREATE TRIGGER ad_conversion_outbox_payload_guard BEFORE UPDATE OR DELETE ON ad_conversion_outbox FOR EACH ROW EXECUTE FUNCTION ad_billing_outbox_payload_immutable();

CREATE FUNCTION ad_conversion_settlement_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'conversion settlement retention is governed'; END IF;
    IF (NEW.qualification_id,NEW.advertiser_id,NEW.budget_date,NEW.decision,NEW.qualification_token,NEW.prepared_at)
       IS DISTINCT FROM (OLD.qualification_id,OLD.advertiser_id,OLD.budget_date,OLD.decision,OLD.qualification_token,OLD.prepared_at)
       OR OLD.state<>'HELD' OR NEW.version<>OLD.version+1
       OR (OLD.visible_receipt_id IS NOT NULL AND NEW.visible_receipt_id IS DISTINCT FROM OLD.visible_receipt_id)
       OR (OLD.outcome_id IS NOT NULL AND NEW.outcome_id IS DISTINCT FROM OLD.outcome_id)
    THEN RAISE EXCEPTION 'conversion settlement transition is invalid'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ad_conversion_settlement_guard BEFORE UPDATE OR DELETE ON ad_conversion_settlement FOR EACH ROW EXECUTE FUNCTION ad_conversion_settlement_guard();
CREATE FUNCTION ad_conversion_visible_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'conversion receipt retention is governed'; END IF;
    IF OLD.status<>'PENDING' OR (NEW.receipt_id,NEW.qualification_id,NEW.subject_key,NEW.payload_hash,
       NEW.visible_duration_ms,NEW.min_visible_ratio_bps,NEW.page_visible,NEW.created_at)
       IS DISTINCT FROM (OLD.receipt_id,OLD.qualification_id,OLD.subject_key,OLD.payload_hash,
       OLD.visible_duration_ms,OLD.min_visible_ratio_bps,OLD.page_visible,OLD.created_at)
    THEN RAISE EXCEPTION 'conversion receipt decision is immutable'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ad_conversion_visible_guard BEFORE UPDATE OR DELETE ON ad_conversion_visible FOR EACH ROW EXECUTE FUNCTION ad_conversion_visible_guard();
-- 延迟到提交检查，数据库拒绝遗留的PENDING命令，不靠正常代码路径的约定兜底。
CREATE FUNCTION ad_conversion_visible_complete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM ad_conversion_visible WHERE receipt_id=NEW.receipt_id AND status='PENDING')
    THEN RAISE EXCEPTION 'conversion receipt must be decided before commit'; END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER ad_conversion_visible_complete AFTER INSERT OR UPDATE ON ad_conversion_visible
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ad_conversion_visible_complete();

CREATE FUNCTION ad_conversion_outcome_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'conversion outcome retention is governed'; END IF;
    IF OLD.status<>'PENDING' OR (NEW.outcome_id,NEW.advertiser_id,NEW.event_id,NEW.qualification_id,NEW.actor,NEW.payload_hash,NEW.occurred_at,NEW.received_at)
        IS DISTINCT FROM (OLD.outcome_id,OLD.advertiser_id,OLD.event_id,OLD.qualification_id,OLD.actor,OLD.payload_hash,OLD.occurred_at,OLD.received_at)
    THEN RAISE EXCEPTION 'conversion outcome decision is immutable'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ad_conversion_outcome_guard BEFORE UPDATE OR DELETE ON ad_conversion_outcome FOR EACH ROW EXECUTE FUNCTION ad_conversion_outcome_guard();
CREATE FUNCTION ad_conversion_outcome_complete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM ad_conversion_outcome WHERE outcome_id=NEW.outcome_id AND status='PENDING')
    THEN RAISE EXCEPTION 'conversion outcome must be decided before commit'; END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER ad_conversion_outcome_complete AFTER INSERT OR UPDATE ON ad_conversion_outcome
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ad_conversion_outcome_complete();
INSERT INTO ad_billing_schema_history(version,description,script_sha256) VALUES(13,'sandbox atomic CPA settlement',current_setting('recsys.migration_sha256'));
COMMIT;
