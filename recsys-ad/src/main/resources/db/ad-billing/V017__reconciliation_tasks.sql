BEGIN;
CREATE TABLE ad_reconciliation_task (
    task_id uuid PRIMARY KEY,
    advertiser_id bigint NOT NULL CHECK(advertiser_id>0),
    traffic_class varchar(16) NOT NULL DEFAULT 'SANDBOX' CHECK(traffic_class='SANDBOX'),
    currency char(3) NOT NULL DEFAULT 'CNY' CHECK(currency='CNY'),
    start_date date NOT NULL,
    end_date date NOT NULL,
    reason varchar(256) NOT NULL CHECK(length(reason)>0),
    created_by varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    state varchar(16) NOT NULL DEFAULT 'READY' CHECK(state IN ('READY','RUNNING','COMPLETED','FAILED','CANCELLED')),
    next_date date NOT NULL,
    completed_days integer NOT NULL DEFAULT 0 CHECK(completed_days BETWEEN 0 AND 31),
    attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 20),
    version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
    available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_until timestamptz,
    last_failure varchar(32) CHECK(last_failure IN ('SNAPSHOT_FAILED','LEASE_EXHAUSTED')),
    updated_by varchar(128) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    cancel_command_id uuid,
    cancel_reason varchar(256),
    CHECK(end_date>=start_date AND end_date-start_date<31),
    CHECK(next_date=start_date+completed_days AND next_date<=end_date+1),
    CHECK((state='COMPLETED')=(next_date=end_date+1)),
    CHECK((state='RUNNING')=(lease_until IS NOT NULL)),
    CHECK((state='CANCELLED')=(cancel_command_id IS NOT NULL) AND (cancel_command_id IS NULL)=(cancel_reason IS NULL))
);
COMMENT ON TABLE ad_reconciliation_task IS 'Sandbox账户原预算日的持久化只读对账任务';
COMMENT ON COLUMN ad_reconciliation_task.task_id IS '稳定任务命令标识';
COMMENT ON COLUMN ad_reconciliation_task.advertiser_id IS '归属广告主';
COMMENT ON COLUMN ad_reconciliation_task.traffic_class IS '固定沙箱流量';
COMMENT ON COLUMN ad_reconciliation_task.currency IS '固定人民币';
COMMENT ON COLUMN ad_reconciliation_task.start_date IS '冻结的起始原预算日';
COMMENT ON COLUMN ad_reconciliation_task.end_date IS '冻结的结束原预算日';
COMMENT ON COLUMN ad_reconciliation_task.reason IS '创建原因';
COMMENT ON COLUMN ad_reconciliation_task.created_by IS '首次可信创建主体';
COMMENT ON COLUMN ad_reconciliation_task.created_at IS '数据库创建时间';
COMMENT ON COLUMN ad_reconciliation_task.state IS '任务生命周期';
COMMENT ON COLUMN ad_reconciliation_task.next_date IS '下一未完成日期';
COMMENT ON COLUMN ad_reconciliation_task.completed_days IS '已保存日结果数量';
COMMENT ON COLUMN ad_reconciliation_task.attempts IS '当前日期领取次数含崩溃';
COMMENT ON COLUMN ad_reconciliation_task.version IS '单调状态版本及领取隔离令牌';
COMMENT ON COLUMN ad_reconciliation_task.available_at IS '下次允许领取的数据库时间';
COMMENT ON COLUMN ad_reconciliation_task.lease_until IS '执行租约到期时间';
COMMENT ON COLUMN ad_reconciliation_task.last_failure IS '固定安全错误类型';
COMMENT ON COLUMN ad_reconciliation_task.updated_by IS '最后可信操作或系统主体';
COMMENT ON COLUMN ad_reconciliation_task.updated_at IS '最后状态更新时间';
COMMENT ON COLUMN ad_reconciliation_task.cancel_command_id IS '最终取消命令标识';
COMMENT ON COLUMN ad_reconciliation_task.cancel_reason IS '最终取消原因';
CREATE TABLE ad_reconciliation_day (
    task_id uuid NOT NULL REFERENCES ad_reconciliation_task(task_id),
    budget_date date NOT NULL,
    fence bigint NOT NULL CHECK(fence>0),
    report_version integer NOT NULL CHECK(report_version=1),
    report_json jsonb NOT NULL,
    observed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(task_id,budget_date)
);
COMMENT ON TABLE ad_reconciliation_day IS '不可变的逐日对账观察结果';
COMMENT ON COLUMN ad_reconciliation_day.task_id IS '归属任务';
COMMENT ON COLUMN ad_reconciliation_day.budget_date IS '原预算日';
COMMENT ON COLUMN ad_reconciliation_day.fence IS '实际保存结果的领取版本';
COMMENT ON COLUMN ad_reconciliation_day.report_version IS '内部持久化报告格式版本';
COMMENT ON COLUMN ad_reconciliation_day.report_json IS '完整原始一致快照';
COMMENT ON COLUMN ad_reconciliation_day.observed_at IS '该日期独立快照观察时间';
COMMENT ON COLUMN ad_reconciliation_day.created_at IS '结果落库时间';
CREATE TABLE ad_reconciliation_audit (
    task_id uuid NOT NULL REFERENCES ad_reconciliation_task(task_id),
    version bigint NOT NULL,
    event_type varchar(32) NOT NULL CHECK(event_type IN ('CREATED','CLAIMED','DAY_SAVED','RETRY_SCHEDULED','FAILED','CANCELLED')),
    actor varchar(128) NOT NULL,
    budget_date date NOT NULL,
    attempts integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(task_id,version)
);
COMMENT ON TABLE ad_reconciliation_audit IS '追加不可变的对账任务状态审计';
COMMENT ON COLUMN ad_reconciliation_audit.task_id IS '归属任务和原创建命令';
COMMENT ON COLUMN ad_reconciliation_audit.version IS '状态更新版本';
COMMENT ON COLUMN ad_reconciliation_audit.event_type IS '状态事件类型';
COMMENT ON COLUMN ad_reconciliation_audit.actor IS '可信操作或固定系统主体';
COMMENT ON COLUMN ad_reconciliation_audit.budget_date IS '此次动作对应预算日';
COMMENT ON COLUMN ad_reconciliation_audit.attempts IS '动作发生时本日领取次数';
COMMENT ON COLUMN ad_reconciliation_audit.created_at IS '数据库审计时间';
CREATE UNIQUE INDEX ad_reconciliation_one_active ON ad_reconciliation_task(advertiser_id) WHERE state IN ('READY','RUNNING');
COMMENT ON INDEX ad_reconciliation_one_active IS '单广告主最多一个活跃任务且不阻塞其他广告主';
CREATE TRIGGER ad_reconciliation_day_immutable BEFORE UPDATE OR DELETE ON ad_reconciliation_day FOR EACH ROW EXECUTE FUNCTION ad_billing_ledger_immutable();
CREATE TRIGGER ad_reconciliation_audit_immutable BEFORE UPDATE OR DELETE ON ad_reconciliation_audit FOR EACH ROW EXECUTE FUNCTION ad_billing_ledger_immutable();
INSERT INTO ad_billing_schema_history(version,description,script_sha256) VALUES(17,'sandbox reconciliation tasks',current_setting('recsys.migration_sha256'));
COMMIT;
