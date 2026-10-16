BEGIN;
CREATE TABLE engine_seen_schema_history (
    version INTEGER PRIMARY KEY,
    description TEXT NOT NULL,
    script_sha256 CHAR(64) NOT NULL CHECK (script_sha256 ~ '^[0-9a-f]{64}$'),
    installed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE engine_seen_schema_history IS 'engine已看恢复独立迁移历史';
COMMENT ON COLUMN engine_seen_schema_history.version IS '迁移版本';
COMMENT ON COLUMN engine_seen_schema_history.description IS '迁移用途';
COMMENT ON COLUMN engine_seen_schema_history.script_sha256 IS '实际脚本摘要';
COMMENT ON COLUMN engine_seen_schema_history.installed_at IS '安装数据库时间';
CREATE TABLE seen_stream_control (
    stream VARCHAR(16) PRIMARY KEY CHECK (stream IN ('LEGACY','LIVE','HISTORY')),
    cluster_id TEXT NOT NULL,
    topic_id TEXT NOT NULL,
    topic VARCHAR(249) NOT NULL,
    group_id VARCHAR(200) NOT NULL,
    initializing_command_id UUID,
    stop_reason VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(cluster_id,topic_id,group_id)
);
COMMENT ON TABLE seen_stream_control IS '已看流绑定与初始化跨命令排他所有权';
COMMENT ON COLUMN seen_stream_control.stream IS '固定逻辑流';
COMMENT ON COLUMN seen_stream_control.cluster_id IS 'Kafka集群身份';
COMMENT ON COLUMN seen_stream_control.topic_id IS '不可变topic身份';
COMMENT ON COLUMN seen_stream_control.topic IS '真实topic名';
COMMENT ON COLUMN seen_stream_control.group_id IS '消费组身份';
COMMENT ON COLUMN seen_stream_control.initializing_command_id IS '副作用前持久占有的初始化命令';
COMMENT ON COLUMN seen_stream_control.stop_reason IS '需显式核验后解除的停止原因';
COMMENT ON COLUMN seen_stream_control.created_at IS '首次绑定时间';
CREATE TABLE seen_recovery_command (
    command_id UUID PRIMARY KEY,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('INITIALIZE','RETRY','RECHECK','RESTORE')),
    stream VARCHAR(16) NOT NULL REFERENCES seen_stream_control(stream),
    record_id UUID,
    request_json TEXT NOT NULL CHECK (octet_length(request_json)<=65536),
    request_digest CHAR(64) NOT NULL CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    actor VARCHAR(200) NOT NULL CHECK (length(btrim(actor))>0),
    reason VARCHAR(1000) NOT NULL CHECK (length(btrim(reason))>0),
    status VARCHAR(16) NOT NULL DEFAULT 'READY' CHECK (status IN ('READY','RUNNING','SUCCEEDED','FAILED')),
    result VARCHAR(40),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 3),
    fence BIGINT NOT NULL DEFAULT 0 CHECK (fence>=0),
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    before_status VARCHAR(16),
    before_epoch BIGINT,
    before_attempts INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ
);
COMMENT ON TABLE seen_recovery_command IS '已看恢复稳定命令与不可变审计';
COMMENT ON COLUMN seen_recovery_command.command_id IS '受控命令标识';
COMMENT ON COLUMN seen_recovery_command.kind IS '命令用途';
COMMENT ON COLUMN seen_recovery_command.stream IS '目标流';
COMMENT ON COLUMN seen_recovery_command.record_id IS '单条恢复目标';
COMMENT ON COLUMN seen_recovery_command.request_json IS '不可变规范请求和冻结清单';
COMMENT ON COLUMN seen_recovery_command.request_digest IS '请求摘要';
COMMENT ON COLUMN seen_recovery_command.actor IS '可信调用主体';
COMMENT ON COLUMN seen_recovery_command.reason IS '操作原因';
COMMENT ON COLUMN seen_recovery_command.status IS '命令状态';
COMMENT ON COLUMN seen_recovery_command.result IS '固定结果分类';
COMMENT ON COLUMN seen_recovery_command.attempts IS '跨进程累计尝试';
COMMENT ON COLUMN seen_recovery_command.fence IS '旧执行器隔离令牌';
COMMENT ON COLUMN seen_recovery_command.lease_until IS '当前领取到期时间';
COMMENT ON COLUMN seen_recovery_command.next_attempt_at IS '最早续办时间';
COMMENT ON COLUMN seen_recovery_command.before_status IS '首次恢复前状态';
COMMENT ON COLUMN seen_recovery_command.before_epoch IS '首次恢复前轮次';
COMMENT ON COLUMN seen_recovery_command.before_attempts IS '首次恢复前已用预算';
COMMENT ON COLUMN seen_recovery_command.created_at IS '首次登记时间';
COMMENT ON COLUMN seen_recovery_command.completed_at IS '固定终态时间';
CREATE TABLE seen_stream_partition (
    stream VARCHAR(16) NOT NULL REFERENCES seen_stream_control(stream),
    partition_id INTEGER NOT NULL CHECK (partition_id BETWEEN 0 AND 31),
    cluster_id TEXT NOT NULL,
    topic_id TEXT NOT NULL,
    topic VARCHAR(249) NOT NULL,
    group_id VARCHAR(200) NOT NULL,
    bootstrap_offset BIGINT NOT NULL CHECK (bootstrap_offset>=0),
    command_id UUID NOT NULL REFERENCES seen_recovery_command(command_id),
    observed_offset BIGINT NOT NULL CHECK (observed_offset>=0),
    PRIMARY KEY(stream,partition_id),
    UNIQUE(cluster_id,topic_id,group_id,partition_id)
);
COMMENT ON TABLE seen_stream_partition IS '已成功初始化的不可变分区起点';
COMMENT ON COLUMN seen_stream_partition.stream IS '所属流';
COMMENT ON COLUMN seen_stream_partition.partition_id IS '分区编号';
COMMENT ON COLUMN seen_stream_partition.cluster_id IS '集群身份';
COMMENT ON COLUMN seen_stream_partition.topic_id IS 'topic身份';
COMMENT ON COLUMN seen_stream_partition.topic IS '真实topic名称';
COMMENT ON COLUMN seen_stream_partition.group_id IS '消费组';
COMMENT ON COLUMN seen_stream_partition.bootstrap_offset IS '命令冻结起点';
COMMENT ON COLUMN seen_stream_partition.command_id IS '成功登记命令';
COMMENT ON COLUMN seen_stream_partition.observed_offset IS '初始化读回位点证据';
CREATE TABLE seen_record (
    record_id UUID PRIMARY KEY,
    stream VARCHAR(16) NOT NULL REFERENCES seen_stream_control(stream),
    cluster_id TEXT NOT NULL,
    topic_id TEXT NOT NULL,
    topic VARCHAR(249) NOT NULL,
    group_id VARCHAR(200) NOT NULL,
    partition_id INTEGER NOT NULL CHECK (partition_id BETWEEN 0 AND 31),
    record_offset BIGINT NOT NULL CHECK (record_offset>=0),
    key_bytes BYTEA CHECK (octet_length(key_bytes)<=1024),
    value_bytes BYTEA CHECK (octet_length(value_bytes)<=32768),
    key_length INTEGER NOT NULL CHECK (key_length>=-1),
    value_length INTEGER NOT NULL CHECK (value_length>=-1),
    digest CHAR(64) NOT NULL CHECK (digest ~ '^[0-9a-f]{64}$'),
    payload_complete BOOLEAN NOT NULL,
    first_classification VARCHAR(16) NOT NULL CHECK (first_classification IN ('POSITIVE','NO_EFFECT','INVALID_DATA')),
    first_error VARCHAR(40),
    status VARCHAR(16) NOT NULL CHECK (status IN ('READY','APPLYING','APPLIED','NO_EFFECT','QUARANTINED','PAUSED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 3),
    epoch BIGINT NOT NULL DEFAULT 0 CHECK (epoch>=0),
    fence BIGINT NOT NULL DEFAULT 0 CHECK (fence>=0),
    lease_until TIMESTAMPTZ,
    active_command_id UUID REFERENCES seen_recovery_command(command_id),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_error VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_success_at TIMESTAMPTZ,
    UNIQUE(cluster_id,topic_id,group_id,partition_id,record_offset),
    CHECK (NOT payload_complete OR (key_length=COALESCE(octet_length(key_bytes),-1) AND value_length=COALESCE(octet_length(value_bytes),-1))),
    CHECK (payload_complete OR (key_bytes IS NULL AND value_bytes IS NULL)),
    CHECK (status<>'APPLIED' OR last_success_at IS NOT NULL)
);
COMMENT ON TABLE seen_record IS '已看消费技术原文与持久处理预算，不是业务事件去重表';
COMMENT ON COLUMN seen_record.record_id IS '技术记录ID';
COMMENT ON COLUMN seen_record.stream IS '逻辑流';
COMMENT ON COLUMN seen_record.cluster_id IS 'Kafka集群身份';
COMMENT ON COLUMN seen_record.topic_id IS 'topic身份';
COMMENT ON COLUMN seen_record.topic IS '真实topic名';
COMMENT ON COLUMN seen_record.group_id IS '消费组';
COMMENT ON COLUMN seen_record.partition_id IS '分区编号';
COMMENT ON COLUMN seen_record.record_offset IS '消息位点';
COMMENT ON COLUMN seen_record.key_bytes IS '有界原key，null标记另存';
COMMENT ON COLUMN seen_record.value_bytes IS '有界原value，非法UTF8不替换';
COMMENT ON COLUMN seen_record.key_length IS '原key长度，负一表示null';
COMMENT ON COLUMN seen_record.value_length IS '原value长度，负一表示null';
COMMENT ON COLUMN seen_record.digest IS '带null与长度分隔的原字节摘要';
COMMENT ON COLUMN seen_record.payload_complete IS '是否保存完整原文';
COMMENT ON COLUMN seen_record.first_classification IS '首次解析分类不可变';
COMMENT ON COLUMN seen_record.first_error IS '首次固定错误不可变';
COMMENT ON COLUMN seen_record.status IS '当前履行结果';
COMMENT ON COLUMN seen_record.attempts IS '本轮持久尝试预算';
COMMENT ON COLUMN seen_record.epoch IS '显式恢复开启的轮次';
COMMENT ON COLUMN seen_record.fence IS '每次领取递增令牌';
COMMENT ON COLUMN seen_record.lease_until IS '数据库时钟租约';
COMMENT ON COLUMN seen_record.active_command_id IS '独占恢复占有';
COMMENT ON COLUMN seen_record.next_attempt_at IS '短退避截止';
COMMENT ON COLUMN seen_record.last_error IS '最近固定错误码';
COMMENT ON COLUMN seen_record.created_at IS '首次技术登记时间';
COMMENT ON COLUMN seen_record.last_success_at IS '最后一次真实SADD成功持久时间';
ALTER TABLE seen_record ADD CONSTRAINT seen_record_partition_fk FOREIGN KEY(stream,partition_id) REFERENCES seen_stream_partition(stream,partition_id);
ALTER TABLE seen_recovery_command ADD CONSTRAINT seen_command_record_fk FOREIGN KEY(record_id) REFERENCES seen_record(record_id);
ALTER TABLE seen_stream_control ADD CONSTRAINT seen_initialization_command_fk FOREIGN KEY(initializing_command_id) REFERENCES seen_recovery_command(command_id);
CREATE INDEX seen_pending_idx ON seen_record(stream,status,next_attempt_at);
CREATE INDEX seen_quarantine_idx ON seen_record(created_at,record_id) WHERE status='QUARANTINED';
CREATE INDEX seen_active_command_idx ON seen_record(stream) WHERE active_command_id IS NOT NULL;
CREATE FUNCTION seen_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'seen immutable evidence'; END IF;
    IF TG_TABLE_NAME='seen_record' THEN
        IF ROW(NEW.record_id,NEW.stream,NEW.cluster_id,NEW.topic_id,NEW.topic,NEW.group_id,NEW.partition_id,NEW.record_offset,
            NEW.key_bytes,NEW.value_bytes,NEW.key_length,NEW.value_length,NEW.digest,NEW.payload_complete,
            NEW.first_classification,NEW.first_error,NEW.created_at)
            IS DISTINCT FROM ROW(OLD.record_id,OLD.stream,OLD.cluster_id,OLD.topic_id,OLD.topic,OLD.group_id,OLD.partition_id,OLD.record_offset,
            OLD.key_bytes,OLD.value_bytes,OLD.key_length,OLD.value_length,OLD.digest,OLD.payload_complete,
            OLD.first_classification,OLD.first_error,OLD.created_at) THEN RAISE EXCEPTION 'seen immutable record'; END IF;
    ELSIF TG_TABLE_NAME='seen_recovery_command' THEN
        IF ROW(NEW.command_id,NEW.kind,NEW.stream,NEW.record_id,NEW.request_json,NEW.request_digest,NEW.actor,NEW.reason,
            NEW.before_status,NEW.before_epoch,NEW.before_attempts,NEW.created_at)
            IS DISTINCT FROM ROW(OLD.command_id,OLD.kind,OLD.stream,OLD.record_id,OLD.request_json,OLD.request_digest,OLD.actor,OLD.reason,
            OLD.before_status,OLD.before_epoch,OLD.before_attempts,OLD.created_at)
            OR (OLD.status IN ('SUCCEEDED','FAILED') AND NEW IS DISTINCT FROM OLD)
            THEN RAISE EXCEPTION 'seen immutable command'; END IF;
    ELSIF TG_TABLE_NAME='seen_stream_control' THEN
        IF ROW(NEW.stream,NEW.cluster_id,NEW.topic_id,NEW.topic,NEW.group_id,NEW.created_at)
            IS DISTINCT FROM ROW(OLD.stream,OLD.cluster_id,OLD.topic_id,OLD.topic,OLD.group_id,OLD.created_at)
            THEN RAISE EXCEPTION 'seen immutable binding'; END IF;
    ELSE RAISE EXCEPTION 'seen immutable partition/history';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER engine_seen_schema_history_immutable BEFORE UPDATE OR DELETE ON engine_seen_schema_history FOR EACH ROW EXECUTE FUNCTION seen_immutable();
CREATE TRIGGER seen_stream_control_immutable BEFORE UPDATE OR DELETE ON seen_stream_control FOR EACH ROW EXECUTE FUNCTION seen_immutable();
CREATE TRIGGER seen_recovery_command_immutable BEFORE UPDATE OR DELETE ON seen_recovery_command FOR EACH ROW EXECUTE FUNCTION seen_immutable();
CREATE TRIGGER seen_stream_partition_immutable BEFORE UPDATE OR DELETE ON seen_stream_partition FOR EACH ROW EXECUTE FUNCTION seen_immutable();
CREATE TRIGGER seen_record_immutable BEFORE UPDATE OR DELETE ON seen_record FOR EACH ROW EXECUTE FUNCTION seen_immutable();
INSERT INTO engine_seen_schema_history(version,description,script_sha256) VALUES(1,'Seen可靠消费与审计恢复',current_setting('recsys.migration_sha256'));
COMMIT;
