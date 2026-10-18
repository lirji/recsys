-- C18追加迁移：只由明确运行的同步作业安装，不修改原目标bootstrap。
CREATE TABLE ad_event_sync_checkpoint (
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton), source_id text NOT NULL, source_fingerprint text NOT NULL,
 state text NOT NULL DEFAULT 'READY' CHECK(state IN ('READY','RUNNING','COMPLETE','PAUSED')),
 epoch bigint NOT NULL DEFAULT 1 CHECK(epoch>0), upper_id bigint CHECK(upper_id>=0), cursor_id bigint NOT NULL DEFAULT 0 CHECK(cursor_id>=0),
 worker text, fence bigint NOT NULL DEFAULT 0 CHECK(fence>=0), lease_until timestamptz,
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 3), available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 scanned bigint NOT NULL DEFAULT 0 CHECK(scanned>=0), copied bigint NOT NULL DEFAULT 0 CHECK(copied>=0),
 error_code text, error_id bigint, completed_at timestamptz, updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK(upper_id IS NULL OR cursor_id<=upper_id));
CREATE TABLE ad_event_sync_audit (
 command_id text PRIMARY KEY, action text NOT NULL CHECK(action IN ('REBUILD','RESUME')),
 actor text NOT NULL, reason text NOT NULL, epoch bigint NOT NULL CHECK(epoch>0), created_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE TABLE ad_event_sync_schema (
 version integer PRIMARY KEY CHECK(version=1), script_sha256 text NOT NULL, installed_at timestamptz NOT NULL DEFAULT clock_timestamp());
INSERT INTO ad_event_sync_schema(version,script_sha256) VALUES(1,current_setting('recsys.ad_event_sync.digest'));
COMMENT ON TABLE ad_event_sync_checkpoint IS '广告日志单来源有限扫描轮与可靠分页检查点';
COMMENT ON COLUMN ad_event_sync_checkpoint.singleton IS '单目标唯一检查点';
COMMENT ON COLUMN ad_event_sync_checkpoint.source_id IS '维护方声明的稳定源数据集UUID';
COMMENT ON COLUMN ad_event_sync_checkpoint.source_fingerprint IS '实际源数据库模式表和服务端身份摘要，不存连接凭据';
COMMENT ON COLUMN ad_event_sync_checkpoint.state IS 'READY可续办RUNNING执行COMPLETE本轮完PAUSED待审计恢复';
COMMENT ON COLUMN ad_event_sync_checkpoint.epoch IS '有限核对轮编号，不是消息提交水位';
COMMENT ON COLUMN ad_event_sync_checkpoint.upper_id IS '本轮冻结可见ID上界，可空表示尚未冻结';
COMMENT ON COLUMN ad_event_sync_checkpoint.cursor_id IS '已原子提交效果的分页位置，下一轮从零核对';
COMMENT ON COLUMN ad_event_sync_checkpoint.worker IS '持有租约的执行实例';
COMMENT ON COLUMN ad_event_sync_checkpoint.fence IS '执行器隔离版本';
COMMENT ON COLUMN ad_event_sync_checkpoint.lease_until IS '数据库时间租约截止';
COMMENT ON COLUMN ad_event_sync_checkpoint.attempts IS '当前页恢复轮已使用次数';
COMMENT ON COLUMN ad_event_sync_checkpoint.available_at IS '失败退避结束时间';
COMMENT ON COLUMN ad_event_sync_checkpoint.scanned IS '本轮已提交核对行数';
COMMENT ON COLUMN ad_event_sync_checkpoint.copied IS '本轮已提交新增行数';
COMMENT ON COLUMN ad_event_sync_checkpoint.error_code IS '固定内部失败原因';
COMMENT ON COLUMN ad_event_sync_checkpoint.error_id IS '内容冲突的原事件ID，可空';
COMMENT ON COLUMN ad_event_sync_checkpoint.completed_at IS '最近一个完整核对轮结束时间，不代表全源实时追平';
COMMENT ON COLUMN ad_event_sync_checkpoint.updated_at IS '检查点最后变化时间';
COMMENT ON TABLE ad_event_sync_audit IS '显式重建与恢复命令审计及幂等身份';
COMMENT ON COLUMN ad_event_sync_audit.command_id IS '稳定恢复命令UUID';
COMMENT ON COLUMN ad_event_sync_audit.action IS 'REBUILD重建或RESUME恢复';
COMMENT ON COLUMN ad_event_sync_audit.actor IS '可信维护操作者';
COMMENT ON COLUMN ad_event_sync_audit.reason IS '维护原因';
COMMENT ON COLUMN ad_event_sync_audit.epoch IS '命令首次生效的核对轮';
COMMENT ON COLUMN ad_event_sync_audit.created_at IS '审计时间';
COMMENT ON TABLE ad_event_sync_schema IS '日志同步迁移身份';
COMMENT ON COLUMN ad_event_sync_schema.version IS '迁移版本';
COMMENT ON COLUMN ad_event_sync_schema.script_sha256 IS '已安装迁移资源SHA256';
COMMENT ON COLUMN ad_event_sync_schema.installed_at IS '安装时间';
