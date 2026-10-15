-- ER-S02：仅显式应用到隔离 adDb；调用方须设置 recsys.migration_sha256 为本文件 SHA-256。
-- 使用 psql ON_ERROR_STOP，重复执行由版本唯一键中止，不能静默覆盖历史。
BEGIN;

CREATE TABLE IF NOT EXISTS ad_billing_schema_history (
    version integer PRIMARY KEY,
    description varchar(200) NOT NULL,
    script_sha256 varchar(64) NOT NULL CHECK (script_sha256 ~ '^[0-9a-f]{64}$'),
    installed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE ad_billing_schema_history IS '广告账务独立迁移记录；显式应用后不可改写历史';
COMMENT ON COLUMN ad_billing_schema_history.version IS '不可变迁移版本';
COMMENT ON COLUMN ad_billing_schema_history.description IS '迁移说明';
COMMENT ON COLUMN ad_billing_schema_history.script_sha256 IS '执行脚本 SHA-256，用于检测漂移';
COMMENT ON COLUMN ad_billing_schema_history.installed_at IS '迁移提交时间';

INSERT INTO ad_billing_schema_history (version, description, script_sha256)
VALUES (1, 'sandbox 预算账户与幂等命令', current_setting('recsys.migration_sha256'));

CREATE TABLE ad_budget_account (
    traffic_class varchar(16) NOT NULL CHECK (traffic_class = 'SANDBOX'),
    advertiser_id bigint NOT NULL CHECK (advertiser_id > 0),
    budget_date date NOT NULL,
    currency varchar(3) NOT NULL CHECK (currency = 'CNY'),
    limit_amount numeric(20,6) NOT NULL CHECK (limit_amount BETWEEN 0 AND 99999999999999.999999),
    held_amount numeric(20,6) NOT NULL CHECK (held_amount BETWEEN 0 AND 99999999999999.999999),
    charged_amount numeric(20,6) NOT NULL CHECK (charged_amount BETWEEN 0 AND 99999999999999.999999),
    version bigint NOT NULL CHECK (version >= 0),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (traffic_class, advertiser_id, budget_date, currency),
    CHECK (held_amount + charged_amount <= limit_amount)
);
COMMENT ON TABLE ad_budget_account IS 'sandbox 广告主日预算权威账户';
COMMENT ON COLUMN ad_budget_account.traffic_class IS '受控流量来源，首版仅 sandbox';
COMMENT ON COLUMN ad_budget_account.advertiser_id IS '广告主管理域标识';
COMMENT ON COLUMN ad_budget_account.budget_date IS 'Asia/Shanghai 业务日';
COMMENT ON COLUMN ad_budget_account.currency IS '固定人民币币种';
COMMENT ON COLUMN ad_budget_account.limit_amount IS '日预算上限，元，六位小数';
COMMENT ON COLUMN ad_budget_account.held_amount IS '未结清预占额，元';
COMMENT ON COLUMN ad_budget_account.charged_amount IS '已确认消费额，元';
COMMENT ON COLUMN ad_budget_account.version IS '并发控制版本，首次零版本';
COMMENT ON COLUMN ad_budget_account.updated_at IS '最近账户变更时间';

CREATE TABLE ad_budget_command (
    traffic_class varchar(16) NOT NULL CHECK (traffic_class = 'SANDBOX'),
    advertiser_id bigint NOT NULL CHECK (advertiser_id > 0),
    command_id uuid NOT NULL,
    budget_date date NOT NULL,
    currency varchar(3) NOT NULL CHECK (currency = 'CNY'),
    requested_limit numeric(20,6) NOT NULL CHECK (requested_limit BETWEEN 0 AND 99999999999999.999999),
    expected_version bigint NOT NULL CHECK (expected_version >= 0 AND expected_version < 9223372036854775807),
    actor varchar(200) NOT NULL CHECK (length(btrim(actor)) > 0),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    status varchar(32) NOT NULL CHECK (status IN ('PENDING', 'APPLIED', 'VERSION_CONFLICT', 'BELOW_COMMITTED')),
    result_limit numeric(20,6),
    result_held numeric(20,6),
    result_charged numeric(20,6),
    result_version bigint,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    PRIMARY KEY (traffic_class, advertiser_id, command_id),
    CHECK (status = 'PENDING' OR (
        result_limit IS NOT NULL AND result_held IS NOT NULL AND result_charged IS NOT NULL
        AND result_version IS NOT NULL AND completed_at IS NOT NULL
        AND result_limit BETWEEN 0 AND 99999999999999.999999
        AND result_held BETWEEN 0 AND 99999999999999.999999
        AND result_charged BETWEEN 0 AND 99999999999999.999999
        AND result_held + result_charged <= result_limit AND result_version >= 0))
);
COMMENT ON TABLE ad_budget_command IS 'sandbox 预算命令幂等与审计记录，保留首次执行结果';
COMMENT ON COLUMN ad_budget_command.traffic_class IS '受控流量来源';
COMMENT ON COLUMN ad_budget_command.advertiser_id IS '命令作用域广告主';
COMMENT ON COLUMN ad_budget_command.command_id IS '客户端生成的稳定命令标识';
COMMENT ON COLUMN ad_budget_command.budget_date IS '命令对应预算业务日';
COMMENT ON COLUMN ad_budget_command.currency IS '命令币种';
COMMENT ON COLUMN ad_budget_command.requested_limit IS '期望预算额度，元';
COMMENT ON COLUMN ad_budget_command.expected_version IS '期望账户版本';
COMMENT ON COLUMN ad_budget_command.actor IS '可信管理端认证操作主体';
COMMENT ON COLUMN ad_budget_command.payload_hash IS '规范化请求 SHA-256';
COMMENT ON COLUMN ad_budget_command.status IS '事务内处理中或首次执行结论；正常提交不留 PENDING';
COMMENT ON COLUMN ad_budget_command.result_limit IS '首次执行时的预算上限快照';
COMMENT ON COLUMN ad_budget_command.result_held IS '首次执行时的预占额快照';
COMMENT ON COLUMN ad_budget_command.result_charged IS '首次执行时的消费额快照';
COMMENT ON COLUMN ad_budget_command.result_version IS '首次执行后的账户版本快照';
COMMENT ON COLUMN ad_budget_command.created_at IS '命令首次接收时间';
COMMENT ON COLUMN ad_budget_command.completed_at IS '结果确定时间';

COMMIT;
