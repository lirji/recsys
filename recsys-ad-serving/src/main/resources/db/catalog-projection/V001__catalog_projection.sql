-- C17 追加迁移；显式安装到 ad-serving 自有库，不改旧 bootstrap 或自动清空数据。
CREATE TABLE catalog_projection_control (
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton), source_id text NOT NULL,
 cluster_id text NOT NULL, topic_id text NOT NULL, topic_name text NOT NULL, group_id text NOT NULL,
 state text NOT NULL CHECK(state IN ('READY','RUNNING','PAUSED')), worker_id text,
 fence bigint NOT NULL DEFAULT 0 CHECK(fence>=0), lease_until timestamptz, error_code text,
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE TABLE catalog_projection_position (
 partition_id integer PRIMARY KEY CHECK(partition_id>=0), start_offset bigint NOT NULL CHECK(start_offset>=0),
 next_offset bigint NOT NULL CHECK(next_offset>=start_offset));
CREATE TABLE catalog_projection_receipt (
 partition_id integer NOT NULL REFERENCES catalog_projection_position(partition_id), record_offset bigint NOT NULL CHECK(record_offset>=0),
 record_key bytea, payload bytea, digest text NOT NULL,
 state text NOT NULL CHECK(state IN ('READY','APPLIED','STALE','QUARANTINED')),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 3), error_code text,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(partition_id,record_offset));
CREATE TABLE catalog_projection_inbox (
 event_id text PRIMARY KEY, ad_id bigint NOT NULL CHECK(ad_id>0), aggregate_version bigint NOT NULL CHECK(aggregate_version>0),
 digest text NOT NULL, state text NOT NULL CHECK(state IN ('APPLIED','STALE')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(ad_id,aggregate_version));
CREATE TABLE catalog_projection (
 ad_id bigint PRIMARY KEY CHECK(ad_id>0), source_id text NOT NULL, aggregate_version bigint NOT NULL CHECK(aggregate_version>0),
 digest text NOT NULL, payload bytea NOT NULL, embedding_payload bytea, item_id bigint, deleted boolean NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(source_id,ad_id));
CREATE TABLE catalog_projection_redis_task (
 task_id bigserial PRIMARY KEY, ad_id bigint NOT NULL REFERENCES catalog_projection(ad_id), aggregate_version bigint NOT NULL CHECK(aggregate_version>0),
 old_payload bytea, payload bytea NOT NULL, state text NOT NULL DEFAULT 'READY' CHECK(state IN ('READY','RUNNING','DONE','PAUSED')),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 3), fence bigint NOT NULL DEFAULT 0 CHECK(fence>=0),
 lease_until timestamptz, available_at timestamptz NOT NULL DEFAULT clock_timestamp(), error_code text,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE INDEX catalog_projection_task_ready ON catalog_projection_redis_task(available_at,task_id) WHERE state IN ('READY','RUNNING');
CREATE INDEX catalog_projection_task_ad ON catalog_projection_redis_task(ad_id,task_id) WHERE state<>'DONE';
CREATE TABLE catalog_projection_audit (
 command_id text PRIMARY KEY, kind text NOT NULL CHECK(kind IN ('INITIALIZE','RESUME_CONSUMER','RETRY_TASK','REFRESH_AD','RECHECK_RECORD')),
 target text NOT NULL, actor text NOT NULL, reason text NOT NULL, result_id bigint,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE TABLE catalog_projection_schema (
 version integer PRIMARY KEY CHECK(version=1), script_sha256 text NOT NULL,
 installed_at timestamptz NOT NULL DEFAULT clock_timestamp());
INSERT INTO catalog_projection_schema(version,script_sha256) VALUES(1,current_setting('recsys.catalog.projection.digest'));
-- 初始化与旧写事务通过表锁交接，登记后不能只关应用开关就重新放行无版本写入。
CREATE FUNCTION catalog_projection_write_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM catalog_projection_control) AND current_setting('recsys.catalog.projection.writer',true) IS DISTINCT FROM 'v1' THEN
  RAISE EXCEPTION 'CATALOG_PROJECTION_LEGACY_WRITE_FORBIDDEN' USING ERRCODE='55000';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END;
$$;
COMMENT ON FUNCTION catalog_projection_write_guard() IS '阻止旧目录消费者覆盖已登记的可靠投影，不作为数据库管理员权限隔离';
CREATE TRIGGER catalog_projection_servable_guard BEFORE INSERT OR UPDATE OR DELETE ON ad_servable FOR EACH ROW EXECUTE FUNCTION catalog_projection_write_guard();
CREATE TRIGGER catalog_projection_embedding_guard BEFORE INSERT OR UPDATE OR DELETE ON ad_embedding FOR EACH ROW EXECUTE FUNCTION catalog_projection_write_guard();
COMMENT ON TABLE catalog_projection_control IS '目录可靠消费单数据集身份与持久执行租约';
COMMENT ON COLUMN catalog_projection_control.singleton IS '固定单行控制主键';
COMMENT ON COLUMN catalog_projection_control.source_id IS '不可替换的目录数据集身份';
COMMENT ON COLUMN catalog_projection_control.cluster_id IS '已登记 Kafka 集群身份';
COMMENT ON COLUMN catalog_projection_control.topic_id IS '已登记 Kafka topic UUID';
COMMENT ON COLUMN catalog_projection_control.topic_name IS '已登记 topic 名称';
COMMENT ON COLUMN catalog_projection_control.group_id IS '独立消费组';
COMMENT ON COLUMN catalog_projection_control.state IS '消费准入状态';
COMMENT ON COLUMN catalog_projection_control.worker_id IS '当前执行实例';
COMMENT ON COLUMN catalog_projection_control.fence IS '租约 fencing 版本';
COMMENT ON COLUMN catalog_projection_control.lease_until IS '数据库时间租约到期';
COMMENT ON COLUMN catalog_projection_control.error_code IS '固定暂停原因';
COMMENT ON COLUMN catalog_projection_control.updated_at IS '最后控制变更时间';
COMMENT ON TABLE catalog_projection_position IS '与目录效果同事务提交的分区恢复位置';
COMMENT ON COLUMN catalog_projection_position.partition_id IS '登记分区号';
COMMENT ON COLUMN catalog_projection_position.start_offset IS '首次保留起点';
COMMENT ON COLUMN catalog_projection_position.next_offset IS '下一条尚未提交效果的偏移';
COMMENT ON TABLE catalog_projection_receipt IS '原始投递与持久重试及隔离证据';
COMMENT ON COLUMN catalog_projection_receipt.partition_id IS '原分区号';
COMMENT ON COLUMN catalog_projection_receipt.record_offset IS '原消息偏移';
COMMENT ON COLUMN catalog_projection_receipt.record_key IS '原 Kafka key 字节，可空';
COMMENT ON COLUMN catalog_projection_receipt.payload IS '原消息字节，可空';
COMMENT ON COLUMN catalog_projection_receipt.digest IS '原 key 和 payload 摘要';
COMMENT ON COLUMN catalog_projection_receipt.state IS '接受处理或隔离状态';
COMMENT ON COLUMN catalog_projection_receipt.attempts IS '当前恢复轮已使用尝试次数';
COMMENT ON COLUMN catalog_projection_receipt.error_code IS '固定隔离原因';
COMMENT ON COLUMN catalog_projection_receipt.created_at IS '首次捕获时间';
COMMENT ON TABLE catalog_projection_inbox IS '事件身份与原始内容去重，不覆盖同版本冲突';
COMMENT ON COLUMN catalog_projection_inbox.event_id IS '生产者稳定事件身份';
COMMENT ON COLUMN catalog_projection_inbox.ad_id IS '原广告标识';
COMMENT ON COLUMN catalog_projection_inbox.aggregate_version IS '原广告聚合版本';
COMMENT ON COLUMN catalog_projection_inbox.digest IS '原 payload 摘要';
COMMENT ON COLUMN catalog_projection_inbox.state IS '已应用或过期事件';
COMMENT ON COLUMN catalog_projection_inbox.created_at IS '接受数据库时间';
COMMENT ON TABLE catalog_projection IS '目录单调版本与删除墓碑，删除后永久保留';
COMMENT ON COLUMN catalog_projection.ad_id IS '广告标识';
COMMENT ON COLUMN catalog_projection.source_id IS '冻结源数据集';
COMMENT ON COLUMN catalog_projection.aggregate_version IS '已接受的最高目录版本';
COMMENT ON COLUMN catalog_projection.digest IS '该版本原 payload 摘要';
COMMENT ON COLUMN catalog_projection.payload IS '完整原快照字节';
COMMENT ON COLUMN catalog_projection.embedding_payload IS '同item最近有效向量的原事件字节，暂停保留、换item或删除清除';
COMMENT ON COLUMN catalog_projection.item_id IS '快照关联物料，删除时为空';
COMMENT ON COLUMN catalog_projection.deleted IS '真实删除标记，暂停不等于删除';
COMMENT ON COLUMN catalog_projection.updated_at IS '本次投影数据库时间';
COMMENT ON TABLE catalog_projection_redis_task IS '按广告串行的 Redis 可靠刷新义务';
COMMENT ON COLUMN catalog_projection_redis_task.task_id IS '持久任务身份，同时作 Redis 刷新代际';
COMMENT ON COLUMN catalog_projection_redis_task.ad_id IS '目标广告';
COMMENT ON COLUMN catalog_projection_redis_task.aggregate_version IS '任务目录版本';
COMMENT ON COLUMN catalog_projection_redis_task.old_payload IS '应用前目录快照，可空';
COMMENT ON COLUMN catalog_projection_redis_task.payload IS '应用后目录快照';
COMMENT ON COLUMN catalog_projection_redis_task.state IS '领取执行完成或暂停状态';
COMMENT ON COLUMN catalog_projection_redis_task.attempts IS '当前恢复轮已领取次数';
COMMENT ON COLUMN catalog_projection_redis_task.fence IS '任务领取 fencing 版本';
COMMENT ON COLUMN catalog_projection_redis_task.lease_until IS '任务数据库租约到期时间';
COMMENT ON COLUMN catalog_projection_redis_task.available_at IS '退避后最早领取时间';
COMMENT ON COLUMN catalog_projection_redis_task.error_code IS '固定失败原因';
COMMENT ON COLUMN catalog_projection_redis_task.created_at IS '任务创建时间';
COMMENT ON COLUMN catalog_projection_redis_task.updated_at IS '最后任务变更时间';
COMMENT ON TABLE catalog_projection_audit IS '不可变内部恢复命令审计及原结果';
COMMENT ON COLUMN catalog_projection_audit.command_id IS '调用方稳定 UUID';
COMMENT ON COLUMN catalog_projection_audit.kind IS '封闭恢复命令类型';
COMMENT ON COLUMN catalog_projection_audit.target IS '冻结目标和初始化清单摘要';
COMMENT ON COLUMN catalog_projection_audit.actor IS '受控内部操作者';
COMMENT ON COLUMN catalog_projection_audit.reason IS '操作理由';
COMMENT ON COLUMN catalog_projection_audit.result_id IS '原操作生成的任务身份';
COMMENT ON COLUMN catalog_projection_audit.created_at IS '审计提交时间';
COMMENT ON TABLE catalog_projection_schema IS 'C17 显式迁移版本登记';
COMMENT ON COLUMN catalog_projection_schema.version IS '已安装迁移版本';
COMMENT ON COLUMN catalog_projection_schema.script_sha256 IS '显式安装器核验的原脚本 SHA256';
COMMENT ON COLUMN catalog_projection_schema.installed_at IS '安装数据库时间';
