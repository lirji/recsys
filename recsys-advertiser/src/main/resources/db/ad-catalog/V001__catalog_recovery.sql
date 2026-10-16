-- C16：必须在各实际物理库显式安装，不经ShardingSphere/SINGLE自动迁移。
-- 安装器在同一事务设置 recsys.catalog.install_shard 和 recsys.catalog.install_sha256。
DO $$ BEGIN
 IF current_setting('recsys.catalog.install_shard',true) IS NULL
    OR current_setting('recsys.catalog.install_shard') NOT IN ('0','1')
    OR length(coalesce(current_setting('recsys.catalog.install_sha256',true),''))<>64 THEN
   RAISE EXCEPTION 'CATALOG_INSTALL_CONTEXT_REQUIRED';
 END IF;
END $$;

CREATE TABLE advertiser_catalog_schema_history (
  shard_id smallint NOT NULL CHECK (shard_id IN (0,1)),
  version integer NOT NULL,
  description text NOT NULL,
  script_sha256 text NOT NULL CHECK (length(script_sha256)=64),
  installed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(shard_id,version)
);
COMMENT ON TABLE advertiser_catalog_schema_history IS '广告目录本物理库迁移记录';
COMMENT ON COLUMN advertiser_catalog_schema_history.shard_id IS '实际物理分片，必须显式路由';
COMMENT ON COLUMN advertiser_catalog_schema_history.version IS '迁移版本';
COMMENT ON COLUMN advertiser_catalog_schema_history.description IS '迁移说明';
COMMENT ON COLUMN advertiser_catalog_schema_history.script_sha256 IS '实际迁移脚本摘要';
COMMENT ON COLUMN advertiser_catalog_schema_history.installed_at IS '本库安装时间';

CREATE TABLE catalog_shard_control (
  shard_id smallint NOT NULL CHECK (shard_id IN (0,1)),
  source_id uuid,
  topology_version text,
  cluster_id text,
  topic_id text,
  topic_name text,
  mode text NOT NULL DEFAULT 'LEGACY' CHECK (mode IN ('LEGACY','QUIESCED','ACTIVE')),
  initializing_command_id uuid,
  initialization_ready boolean NOT NULL DEFAULT false,
  writer_manifest jsonb NOT NULL DEFAULT '{}',
  stop_reason text,
  revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(shard_id)
);
COMMENT ON TABLE catalog_shard_control IS '本物理分片准入和初始化控制';
COMMENT ON COLUMN catalog_shard_control.shard_id IS '实际物理分片，必须显式路由';
COMMENT ON COLUMN catalog_shard_control.source_id IS '登记后不可替换的数据集身份';
COMMENT ON COLUMN catalog_shard_control.topology_version IS '冻结路由版本';
COMMENT ON COLUMN catalog_shard_control.cluster_id IS '冻结Kafka集群身份';
COMMENT ON COLUMN catalog_shard_control.topic_id IS '冻结Kafka主题身份';
COMMENT ON COLUMN catalog_shard_control.topic_name IS '冻结Kafka主题名';
COMMENT ON COLUMN catalog_shard_control.mode IS '旧模式、封闭基线或可靠写入';
COMMENT ON COLUMN catalog_shard_control.initializing_command_id IS '跨初始化命令持久占有';
COMMENT ON COLUMN catalog_shard_control.initialization_ready IS '本次初始化全部步骤已核实';
COMMENT ON COLUMN catalog_shard_control.writer_manifest IS '冻结实例与worker-id清单';
COMMENT ON COLUMN catalog_shard_control.stop_reason IS '需显式核验的安全停止原因';
COMMENT ON COLUMN catalog_shard_control.revision IS '控制修订号';
COMMENT ON COLUMN catalog_shard_control.updated_at IS '本库最近更新时间';

CREATE TABLE catalog_advertiser_guard (
  shard_id smallint NOT NULL CHECK (shard_id IN (0,1)),
  advertiser_id bigint NOT NULL CHECK(advertiser_id>0),
  advertiser_version bigint NOT NULL DEFAULT 1 CHECK(advertiser_version>0),
  active_command_id uuid,
  active_command_home smallint CHECK(active_command_home IN (0,1)),
  active_command_scope text,
  deleted boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(shard_id,advertiser_id), CHECK(shard_id=advertiser_id%2), CHECK((active_command_id IS NULL)=(active_command_home IS NULL)), CHECK((active_command_id IS NULL)=(active_command_scope IS NULL))
);
COMMENT ON TABLE catalog_advertiser_guard IS '广告主跨命令持久排他';
COMMENT ON COLUMN catalog_advertiser_guard.shard_id IS '实际物理分片，必须显式路由';
COMMENT ON COLUMN catalog_advertiser_guard.advertiser_id IS '广告主身份';
COMMENT ON COLUMN catalog_advertiser_guard.advertiser_version IS '根事实版本';
COMMENT ON COLUMN catalog_advertiser_guard.active_command_id IS '占有者命令，租约到期不释放';
COMMENT ON COLUMN catalog_advertiser_guard.active_command_home IS '占有者固定命令home';
COMMENT ON COLUMN catalog_advertiser_guard.active_command_scope IS '占有者固定作用域';
COMMENT ON COLUMN catalog_advertiser_guard.deleted IS '删除预留标记';
COMMENT ON COLUMN catalog_advertiser_guard.created_at IS '本库创建时间';
COMMENT ON COLUMN catalog_advertiser_guard.updated_at IS '本库最近更新时间';

CREATE TABLE catalog_identity (
  shard_id smallint NOT NULL CHECK (shard_id IN (0,1)),
  source_id uuid NOT NULL,
  command_id uuid NOT NULL,
  entity_id bigint NOT NULL CHECK(entity_id>0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(shard_id,source_id,command_id), UNIQUE(entity_id)
);
COMMENT ON TABLE catalog_identity IS '开户原Snowflake身份预留';
COMMENT ON COLUMN catalog_identity.shard_id IS '实际物理分片，必须显式路由';
COMMENT ON COLUMN catalog_identity.source_id IS '持久目录数据集身份';
COMMENT ON COLUMN catalog_identity.command_id IS '稳定业务命令身份';
COMMENT ON COLUMN catalog_identity.entity_id IS '首次生成的开户身份，未知提交不可换号';
COMMENT ON COLUMN catalog_identity.created_at IS '本库创建时间';

CREATE TABLE catalog_aggregate (
  shard_id smallint NOT NULL CHECK (shard_id IN (0,1)),
  ad_id bigint NOT NULL CHECK(ad_id>0),
  advertiser_id bigint NOT NULL CHECK(advertiser_id>0),
  reserved_version bigint NOT NULL CHECK(reserved_version>0),
  last_materialized_version bigint NOT NULL DEFAULT 0 CHECK(last_materialized_version>=0),
  deleted boolean NOT NULL DEFAULT false,
  last_event_id text,
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(shard_id,ad_id), CHECK(shard_id=advertiser_id%2), CHECK(last_materialized_version<=reserved_version)
);
COMMENT ON TABLE catalog_aggregate IS '广告版本与永久墓碑';
COMMENT ON COLUMN catalog_aggregate.shard_id IS '实际物理分片，必须显式路由';
COMMENT ON COLUMN catalog_aggregate.ad_id IS '广告身份';
COMMENT ON COLUMN catalog_aggregate.advertiser_id IS '原广告主归属';
COMMENT ON COLUMN catalog_aggregate.reserved_version IS '已经预留的最大版本';
COMMENT ON COLUMN catalog_aggregate.last_materialized_version IS '已经持久化完整事件的版本';
COMMENT ON COLUMN catalog_aggregate.deleted IS '权威广告删除';
COMMENT ON COLUMN catalog_aggregate.last_event_id IS '最近已持久化事件';
COMMENT ON COLUMN catalog_aggregate.updated_at IS '本库最近更新时间';

CREATE TABLE catalog_command (
  shard_id smallint NOT NULL CHECK (shard_id IN (0,1)),
  source_id uuid NOT NULL,
  scope_kind text NOT NULL CHECK(scope_kind IN ('ADVERTISER','ROOT_CREATE','INITIALIZE','RECOVERY','STATUS_BATCH')),
  scope_id text NOT NULL,
  command_id uuid NOT NULL,
  kind text NOT NULL,
  request_bytes bytea NOT NULL CHECK(octet_length(request_bytes)<=524288),
  request_sha256 text NOT NULL CHECK(length(request_sha256)=64),
  actor text NOT NULL,
  reason text NOT NULL,
  advertiser_id bigint,
  ad_id bigint,
  state text NOT NULL DEFAULT 'ADMITTED' CHECK(state IN ('ADMITTED','RUNNING','COMPLETED','PAUSED','REJECTED')),
  phase text NOT NULL DEFAULT 'PREPARE',
  active_step text,
  round integer NOT NULL DEFAULT 1 CHECK(round>0),
  attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 3),
  fence bigint NOT NULL DEFAULT 0 CHECK(fence>=0),
  lease_until timestamptz,
  available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  frozen_bytes bytea,
  result_bytes bytea,
  error_type text,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(shard_id,source_id,scope_kind,scope_id,command_id), CHECK(state<>'COMPLETED' OR result_bytes IS NOT NULL)
);
COMMENT ON TABLE catalog_command IS '跨库目录命令及有界协调检查点';
COMMENT ON COLUMN catalog_command.shard_id IS '实际物理分片，必须显式路由';
COMMENT ON COLUMN catalog_command.source_id IS '持久目录数据集身份';
COMMENT ON COLUMN catalog_command.scope_kind IS '固定作用域类型';
COMMENT ON COLUMN catalog_command.scope_id IS '固定作用域身份';
COMMENT ON COLUMN catalog_command.command_id IS '稳定业务命令身份';
COMMENT ON COLUMN catalog_command.kind IS '已批准操作类型';
COMMENT ON COLUMN catalog_command.request_bytes IS '原规范化请求字节';
COMMENT ON COLUMN catalog_command.request_sha256 IS '原请求摘要';
COMMENT ON COLUMN catalog_command.actor IS '原可信调用主体';
COMMENT ON COLUMN catalog_command.reason IS '原审计原因';
COMMENT ON COLUMN catalog_command.advertiser_id IS '冻结广告主，开户预留后写入';
COMMENT ON COLUMN catalog_command.ad_id IS '冻结广告身份';
COMMENT ON COLUMN catalog_command.state IS '协调状态';
COMMENT ON COLUMN catalog_command.phase IS '当前必要步骤';
COMMENT ON COLUMN catalog_command.active_step IS '已持久派发的本地步骤';
COMMENT ON COLUMN catalog_command.round IS '有审计的尝试轮次';
COMMENT ON COLUMN catalog_command.attempts IS '当前步骤已领取次数，退出也计次';
COMMENT ON COLUMN catalog_command.fence IS '协调检查点防迟到令牌';
COMMENT ON COLUMN catalog_command.lease_until IS '本轮数据库租约到期';
COMMENT ON COLUMN catalog_command.available_at IS '下一次允许推进时间';
COMMENT ON COLUMN catalog_command.frozen_bytes IS '已核验的原片段与执行计划';
COMMENT ON COLUMN catalog_command.result_bytes IS '完成后的原响应字节';
COMMENT ON COLUMN catalog_command.error_type IS '脱敏错误分类';
COMMENT ON COLUMN catalog_command.created_at IS '本库创建时间';
COMMENT ON COLUMN catalog_command.updated_at IS '本库最近更新时间';

CREATE TABLE catalog_step (
  shard_id smallint NOT NULL CHECK (shard_id IN (0,1)),
  source_id uuid NOT NULL,
  step_key text NOT NULL CHECK(length(step_key)<=240),
  owner_shard smallint NOT NULL CHECK(owner_shard IN (0,1)),
  command_scope text NOT NULL,
  command_id uuid NOT NULL,
  step_id text NOT NULL,
  advertiser_id bigint NOT NULL CHECK(advertiser_id>0),
  ad_id bigint,
  aggregate_version bigint,
  request_bytes bytea NOT NULL CHECK(octet_length(request_bytes)<=524288),
  request_sha256 text NOT NULL CHECK(length(request_sha256)=64),
  write_plan jsonb NOT NULL,
  state text NOT NULL DEFAULT 'READY' CHECK(state IN ('READY','RUNNING','APPLIED','PAUSED')),
  round integer NOT NULL DEFAULT 1 CHECK(round>0),
  attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 3),
  fence bigint NOT NULL DEFAULT 0 CHECK(fence>=0),
  lease_until timestamptz,
  available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  result_bytes bytea,
  result_sha256 text,
  effects jsonb,
  outbox_id text,
  write_txid text,
  error_type text,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(shard_id,step_key), UNIQUE(shard_id,source_id,command_scope,command_id,step_id), CHECK(state<>'APPLIED' OR (result_bytes IS NOT NULL AND result_sha256 IS NOT NULL AND effects IS NOT NULL AND outbox_id IS NOT NULL AND write_txid IS NOT NULL))
);
COMMENT ON TABLE catalog_step IS '本物理库不可变步骤与独立防迟到令牌';
COMMENT ON COLUMN catalog_step.shard_id IS '实际物理分片，必须显式路由';
COMMENT ON COLUMN catalog_step.source_id IS '持久目录数据集身份';
COMMENT ON COLUMN catalog_step.step_key IS '包含原作用域及步骤的稳定身份';
COMMENT ON COLUMN catalog_step.owner_shard IS '命令home，不推断为本库';
COMMENT ON COLUMN catalog_step.command_scope IS '原命令作用域，包含类型和所属对象';
COMMENT ON COLUMN catalog_step.command_id IS '稳定业务命令身份';
COMMENT ON COLUMN catalog_step.step_id IS '原命令内稳定步骤名称';
COMMENT ON COLUMN catalog_step.advertiser_id IS '冻结广告主归属';
COMMENT ON COLUMN catalog_step.ad_id IS '冻结广告身份，新建回填实际ID';
COMMENT ON COLUMN catalog_step.aggregate_version IS '本步关联广告版本';
COMMENT ON COLUMN catalog_step.request_bytes IS '原步骤字节';
COMMENT ON COLUMN catalog_step.request_sha256 IS '原步骤摘要';
COMMENT ON COLUMN catalog_step.write_plan IS '允许的表、操作及旧目标ID，创建可预先未知ID';
COMMENT ON COLUMN catalog_step.state IS '本地步骤状态';
COMMENT ON COLUMN catalog_step.round IS '有审计的本地轮次';
COMMENT ON COLUMN catalog_step.attempts IS '先持久领取的本轮尝试数';
COMMENT ON COLUMN catalog_step.fence IS '本物理库执行令牌';
COMMENT ON COLUMN catalog_step.lease_until IS '本库执行租约';
COMMENT ON COLUMN catalog_step.available_at IS '可重试时间';
COMMENT ON COLUMN catalog_step.result_bytes IS '不可变本地原结果';
COMMENT ON COLUMN catalog_step.result_sha256 IS '本地原结果摘要';
COMMENT ON COLUMN catalog_step.effects IS '原结果中的实际表、操作与ID证明';
COMMENT ON COLUMN catalog_step.outbox_id IS '同事务本库事实身份';
COMMENT ON COLUMN catalog_step.write_txid IS '完整PG事务号，不能使用32位xid';
COMMENT ON COLUMN catalog_step.error_type IS '安全错误类别';
COMMENT ON COLUMN catalog_step.created_at IS '本库创建时间';
COMMENT ON COLUMN catalog_step.updated_at IS '本库最近更新时间';

CREATE TABLE catalog_outbox (
  shard_id smallint NOT NULL CHECK (shard_id IN (0,1)),
  outbox_id text NOT NULL,
  purpose text NOT NULL CHECK(purpose IN ('COORDINATOR','KAFKA')),
  record_kind text NOT NULL,
  source_id uuid NOT NULL,
  ad_id bigint,
  aggregate_version bigint,
  command_scope text NOT NULL,
  command_id uuid NOT NULL,
  step_id text NOT NULL,
  event_id text,
  topic text,
  key_bytes bytea,
  payload_bytes bytea NOT NULL CHECK(octet_length(payload_bytes) BETWEEN 1 AND 524288),
  payload_sha256 text NOT NULL CHECK(length(payload_sha256)=64),
  write_txid text NOT NULL DEFAULT pg_current_xact_id()::text,
  state text NOT NULL DEFAULT 'READY' CHECK(state IN ('READY','SENDING','PUBLISHED','PAUSED')),
  round integer NOT NULL DEFAULT 1 CHECK(round>0),
  attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 5),
  fence bigint NOT NULL DEFAULT 0 CHECK(fence>=0),
  lease_until timestamptz,
  available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  broker_partition integer,
  broker_offset bigint,
  consumed_by_owner boolean NOT NULL DEFAULT false,
  error_type text,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  published_at timestamptz,
  PRIMARY KEY(shard_id,outbox_id), UNIQUE(shard_id,source_id,command_scope,command_id,step_id,purpose), UNIQUE(shard_id,event_id), CHECK(purpose<>'KAFKA' OR (event_id IS NOT NULL AND ad_id>0 AND aggregate_version>0 AND topic='ad-catalog-v1' AND octet_length(key_bytes)>0)), CHECK(state<>'PUBLISHED' OR (broker_partition IS NOT NULL AND broker_offset IS NOT NULL AND published_at IS NOT NULL))
);
COMMENT ON TABLE catalog_outbox IS '本物理库不可变片段与对外事件';
COMMENT ON COLUMN catalog_outbox.shard_id IS '实际物理分片，必须显式路由';
COMMENT ON COLUMN catalog_outbox.outbox_id IS '稳定事实身份';
COMMENT ON COLUMN catalog_outbox.purpose IS '片段只供协调者核验，完整事件才发Kafka';
COMMENT ON COLUMN catalog_outbox.record_kind IS '根事实、片段或完整事件类型';
COMMENT ON COLUMN catalog_outbox.source_id IS '持久目录数据集身份';
COMMENT ON COLUMN catalog_outbox.ad_id IS '关联广告';
COMMENT ON COLUMN catalog_outbox.aggregate_version IS '关联目录版本';
COMMENT ON COLUMN catalog_outbox.command_scope IS '原命令作用域，包含类型和所属对象';
COMMENT ON COLUMN catalog_outbox.command_id IS '稳定业务命令身份';
COMMENT ON COLUMN catalog_outbox.step_id IS '原本地步骤';
COMMENT ON COLUMN catalog_outbox.event_id IS '稳定完整事件身份';
COMMENT ON COLUMN catalog_outbox.topic IS '固定新主题，片段为空';
COMMENT ON COLUMN catalog_outbox.key_bytes IS '原Kafka键字节';
COMMENT ON COLUMN catalog_outbox.payload_bytes IS '不可变原文';
COMMENT ON COLUMN catalog_outbox.payload_sha256 IS '原文摘要';
COMMENT ON COLUMN catalog_outbox.write_txid IS '本库实际提交事务号';
COMMENT ON COLUMN catalog_outbox.state IS '发送状态';
COMMENT ON COLUMN catalog_outbox.round IS '有审计的发送轮次';
COMMENT ON COLUMN catalog_outbox.attempts IS '先领取的发送次数';
COMMENT ON COLUMN catalog_outbox.fence IS '发送确认防迟到令牌';
COMMENT ON COLUMN catalog_outbox.lease_until IS '发送租约到期';
COMMENT ON COLUMN catalog_outbox.available_at IS '允许再发时间';
COMMENT ON COLUMN catalog_outbox.broker_partition IS '原确认分区';
COMMENT ON COLUMN catalog_outbox.broker_offset IS '原确认偏移';
COMMENT ON COLUMN catalog_outbox.consumed_by_owner IS '片段已被原协调者核验';
COMMENT ON COLUMN catalog_outbox.error_type IS '安全错误分类';
COMMENT ON COLUMN catalog_outbox.created_at IS '本库创建时间';
COMMENT ON COLUMN catalog_outbox.published_at IS '有效确认数据库时间';

CREATE TABLE catalog_task_item (
  shard_id smallint NOT NULL CHECK (shard_id IN (0,1)),
  parent_scope text NOT NULL,
  parent_command_id uuid NOT NULL,
  ordinal bigint NOT NULL CHECK(ordinal>=0),
  target_kind text NOT NULL,
  target_id bigint NOT NULL,
  target_owner_id bigint,
  expected_version bigint,
  child_command_id uuid NOT NULL,
  state text NOT NULL DEFAULT 'READY' CHECK(state IN ('READY','DISPATCHED','COMPLETED','PAUSED','CONFLICT','CANCELLED')),
  result_bytes bytea,
  error_type text,
  page_sha256 text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(shard_id,parent_scope,parent_command_id,target_kind,target_id), UNIQUE(shard_id,parent_scope,parent_command_id,ordinal)
);
COMMENT ON TABLE catalog_task_item IS '初始化及后续批次的冻结有界清单';
COMMENT ON COLUMN catalog_task_item.shard_id IS '实际物理分片，必须显式路由';
COMMENT ON COLUMN catalog_task_item.parent_scope IS '父命令固定作用域';
COMMENT ON COLUMN catalog_task_item.parent_command_id IS '父命令稳定身份';
COMMENT ON COLUMN catalog_task_item.ordinal IS '冻结分页内稳定序号';
COMMENT ON COLUMN catalog_task_item.target_kind IS '目标种类';
COMMENT ON COLUMN catalog_task_item.target_id IS '冻结目标身份';
COMMENT ON COLUMN catalog_task_item.target_owner_id IS '冻结存量广告的真实广告主归属，初始化后不可重采样';
COMMENT ON COLUMN catalog_task_item.expected_version IS '冻结预期版本';
COMMENT ON COLUMN catalog_task_item.child_command_id IS '派生稳定子命令';
COMMENT ON COLUMN catalog_task_item.state IS '逐目标真实进度';
COMMENT ON COLUMN catalog_task_item.result_bytes IS '已核实原结果';
COMMENT ON COLUMN catalog_task_item.error_type IS '逐目标安全错误';
COMMENT ON COLUMN catalog_task_item.page_sha256 IS '冻结页摘要';
COMMENT ON COLUMN catalog_task_item.created_at IS '本库创建时间';
COMMENT ON COLUMN catalog_task_item.updated_at IS '本库最近更新时间';

CREATE UNIQUE INDEX catalog_one_physical_control ON catalog_shard_control((true));
CREATE INDEX catalog_guard_active ON catalog_advertiser_guard(shard_id,active_command_id);
CREATE INDEX catalog_command_work ON catalog_command(shard_id,state,available_at,created_at,command_id);
CREATE INDEX catalog_command_lease ON catalog_command(shard_id,lease_until) WHERE state='RUNNING';
CREATE INDEX catalog_step_work ON catalog_step(shard_id,state,available_at,created_at,step_key);
CREATE INDEX catalog_step_lease ON catalog_step(shard_id,lease_until) WHERE state='RUNNING';
CREATE INDEX catalog_outbox_work ON catalog_outbox(shard_id,purpose,state,available_at,created_at,outbox_id);
CREATE INDEX catalog_outbox_lease ON catalog_outbox(shard_id,lease_until) WHERE state='SENDING';
CREATE INDEX catalog_task_work ON catalog_task_item(shard_id,parent_scope,parent_command_id,state,ordinal);
INSERT INTO catalog_shard_control(shard_id) VALUES(current_setting('recsys.catalog.install_shard')::smallint);
INSERT INTO advertiser_catalog_schema_history(shard_id,version,description,script_sha256)
 VALUES(current_setting('recsys.catalog.install_shard')::smallint,1,'目录物理分片可靠命令与事实',current_setting('recsys.catalog.install_sha256'));

-- 稳定小抖动避免同时到期的命令反复同一时刻竞争，次数由各原记录持久化。
CREATE FUNCTION catalog_backoff(p_attempt integer,p_key text) RETURNS interval
LANGUAGE sql IMMUTABLE STRICT SET search_path=public,pg_catalog AS $$
 SELECT (least(30,power(2,greatest(0,least(p_attempt-1,5))))
   + ((('x'||substr(md5(p_key),1,4))::bit(16)::integer)%200)/1000.0) * interval '1 second';
$$;
COMMENT ON FUNCTION catalog_backoff(integer,text) IS '指数退避加按原身份稳定的0至199毫秒抖动，不重置持久尝试预算';

-- 先锁control、同库guard再锁step；所有业务语句继承同一物理连接的事务局部上下文。
CREATE FUNCTION catalog_enter_step(p_shard integer,p_key text,p_fence bigint) RETURNS text
LANGUAGE plpgsql SET search_path=public,pg_catalog AS $$
DECLARE c catalog_shard_control; s catalog_step; old_key text;
BEGIN
 SELECT * INTO STRICT c FROM catalog_shard_control WHERE shard_id=p_shard FOR SHARE;
 IF c.mode NOT IN ('ACTIVE','QUIESCED') OR c.stop_reason IS NOT NULL THEN
   RAISE EXCEPTION 'CATALOG_NOT_READY';
 END IF;
 -- 输入归属一经登记不可修改，先只读取归属用于取得guard，最后才锁step复验。
 SELECT * INTO STRICT s FROM catalog_step WHERE shard_id=p_shard AND step_key=p_key;
 IF p_shard=s.advertiser_id%2 AND s.command_scope<>'INITIALIZE' THEN
   PERFORM 1 FROM catalog_advertiser_guard WHERE shard_id=p_shard AND advertiser_id=s.advertiser_id
     AND active_command_id=s.command_id AND active_command_home=s.owner_shard AND active_command_scope=s.command_scope FOR UPDATE;
   IF NOT FOUND THEN RAISE EXCEPTION 'CATALOG_OWNER_NOT_HELD'; END IF;
 END IF;
 SELECT * INTO STRICT s FROM catalog_step WHERE shard_id=p_shard AND step_key=p_key FOR UPDATE;
 IF s.state<>'RUNNING' OR s.fence<>p_fence OR s.lease_until<=clock_timestamp()
    OR s.source_id IS DISTINCT FROM c.source_id THEN RAISE EXCEPTION 'CATALOG_STEP_FENCED'; END IF;
 -- QUIESCED仅允许原初始化作用域冻结基线，普通业务不获准写入。
 IF c.mode='QUIESCED' AND (s.command_scope<>'INITIALIZE' OR s.command_id IS DISTINCT FROM c.initializing_command_id)
    THEN RAISE EXCEPTION 'CATALOG_QUIESCED'; END IF;
 old_key:=nullif(current_setting('recsys.catalog.step',true),'');
 IF old_key IS NOT NULL THEN RAISE EXCEPTION 'CATALOG_MIXED_STEP'; END IF;
 -- 初始化本事务的真实效果清单；源表trigger追加，不能凭应用伪造生成ID。
 UPDATE catalog_step SET effects='[]'::jsonb,write_txid=pg_current_xact_id()::text
   WHERE shard_id=p_shard AND step_key=p_key;
 PERFORM set_config('recsys.catalog.step',p_key,true);
 PERFORM set_config('recsys.catalog.fence',p_fence::text,true);
 -- 预算已由CatalogLocalTransactions在第一把control锁前设置，不能在此扩大HTTP剩余预算。
 RETURN pg_current_xact_id()::text;
END $$;
COMMENT ON FUNCTION catalog_enter_step(integer,text,bigint) IS '在实际路由库锁定并绑定唯一事务步骤，不允许无路由函数查询';

-- BEFORE检查不可变输入中的允许操作与真实行归属；新生成ID在延迟检查中验证。
CREATE FUNCTION catalog_guard_write() RETURNS trigger
LANGUAGE plpgsql SET search_path=public,pg_catalog AS $$
DECLARE c catalog_shard_control; s catalog_step; r jsonb; prior jsonb; row_id bigint; parent_id bigint; key_column text;
BEGIN
 SELECT * INTO STRICT c FROM catalog_shard_control FOR SHARE;
 IF c.mode='LEGACY' THEN RETURN coalesce(NEW,OLD); END IF;
 IF TG_OP='TRUNCATE' THEN RAISE EXCEPTION 'CATALOG_TRUNCATE_FORBIDDEN'; END IF;
 IF c.mode<>'ACTIVE' OR NOT c.initialization_ready OR c.stop_reason IS NOT NULL THEN RAISE EXCEPTION 'CATALOG_WRITER_CLOSED'; END IF;
 SELECT * INTO s FROM catalog_step WHERE shard_id=c.shard_id AND step_key=nullif(current_setting('recsys.catalog.step',true),'') FOR UPDATE;
 IF NOT FOUND OR s.state<>'RUNNING' OR s.fence::text IS DISTINCT FROM current_setting('recsys.catalog.fence',true)
    OR s.lease_until<=clock_timestamp() OR s.source_id IS DISTINCT FROM c.source_id THEN
   RAISE EXCEPTION 'CATALOG_WRITE_WITHOUT_STEP';
 END IF;
 r:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
 key_column:=CASE TG_TABLE_NAME WHEN 'advertiser' THEN 'advertiser_id' WHEN 'ad' THEN 'ad_id' WHEN 'bidword' THEN 'id' ELSE 'creative_id' END;
 row_id:=(r->>key_column)::bigint;
 parent_id:=(r->>CASE WHEN TG_TABLE_NAME IN ('advertiser','ad') THEN 'advertiser_id' ELSE 'ad_id' END)::bigint;
 IF TG_TABLE_NAME IN ('advertiser','ad') THEN
   IF parent_id IS DISTINCT FROM s.advertiser_id OR c.shard_id<>parent_id%2 THEN RAISE EXCEPTION 'CATALOG_WRONG_OWNER'; END IF;
   PERFORM 1 FROM catalog_advertiser_guard WHERE shard_id=c.shard_id AND advertiser_id=s.advertiser_id
     AND active_command_id=s.command_id AND active_command_home=s.owner_shard AND active_command_scope=s.command_scope FOR UPDATE;
   IF NOT FOUND THEN RAISE EXCEPTION 'CATALOG_OWNER_NOT_HELD'; END IF;
 ELSE
   IF parent_id IS DISTINCT FROM s.ad_id OR c.shard_id<>parent_id%2 THEN RAISE EXCEPTION 'CATALOG_WRONG_CHILD_SHARD'; END IF;
 END IF;
 IF TG_OP='UPDATE' THEN
   prior:=to_jsonb(OLD);
   IF prior->key_column IS DISTINCT FROM r->key_column
      OR prior->'advertiser_id' IS DISTINCT FROM r->'advertiser_id'
      OR prior->'ad_id' IS DISTINCT FROM r->'ad_id' THEN RAISE EXCEPTION 'CATALOG_IMMUTABLE_OWNERSHIP'; END IF;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM jsonb_array_elements(s.write_plan) p
       WHERE p->>'table'=TG_TABLE_NAME AND p->>'operation'=TG_OP
       AND ((p->>'id')::bigint=row_id OR (TG_OP='INSERT' AND p->>'generated'='true')
            OR (TG_OP='UPDATE' AND p->>'generated'='true' AND EXISTS(
              SELECT 1 FROM jsonb_array_elements(coalesce(s.effects,'[]'::jsonb)) e
               WHERE e->>'table'=TG_TABLE_NAME AND e->>'operation'='INSERT' AND (e->>'id')::bigint=row_id)
              AND s.write_txid=pg_current_xact_id()::text)
            OR (TG_OP='DELETE' AND p->>'allChildren'='true' AND TG_TABLE_NAME IN ('bidword','ad_creative')))) THEN
   RAISE EXCEPTION 'CATALOG_WRITE_NOT_PLANNED';
 END IF;
 -- 同一业务语句失败时此追加也回滚；最后的receipt必须逐项包含真实生效对象。
 UPDATE catalog_step SET effects=effects || jsonb_build_array(jsonb_build_object('table',TG_TABLE_NAME,'operation',TG_OP,'id',row_id))
   WHERE shard_id=c.shard_id AND step_key=s.step_key AND write_txid=pg_current_xact_id()::text;
 IF NOT FOUND THEN RAISE EXCEPTION 'CATALOG_WRONG_TRANSACTION'; END IF;
 RETURN coalesce(NEW,OLD);
END $$;
COMMENT ON FUNCTION catalog_guard_write() IS '可靠模式阻止旧writer和未冻结目标的目录写入，LEGACY仍锁control以便安全封闭';

-- 延迟到实际PG提交：源行、APPLIED receipt和本库Outbox必须处于同一个完整事务号。
CREATE FUNCTION catalog_guard_commit() RETURNS trigger
LANGUAGE plpgsql SET search_path=public,pg_catalog AS $$
DECLARE c catalog_shard_control; s catalog_step; r jsonb; row_id bigint; key_column text;
BEGIN
 SELECT * INTO STRICT c FROM catalog_shard_control;
 IF c.mode='LEGACY' THEN RETURN NULL; END IF;
 SELECT * INTO s FROM catalog_step WHERE shard_id=c.shard_id AND step_key=nullif(current_setting('recsys.catalog.step',true),'');
 IF NOT FOUND OR s.state<>'APPLIED' OR s.write_txid IS DISTINCT FROM pg_current_xact_id()::text
    OR s.fence::text IS DISTINCT FROM current_setting('recsys.catalog.fence',true) THEN RAISE EXCEPTION 'CATALOG_MISSING_LOCAL_RECEIPT'; END IF;
 PERFORM 1 FROM catalog_outbox o WHERE o.shard_id=c.shard_id AND o.outbox_id=s.outbox_id
    AND o.source_id=s.source_id AND o.command_id=s.command_id AND o.command_scope=s.command_scope
    AND o.step_id=s.step_id AND o.write_txid=pg_current_xact_id()::text;
 IF NOT FOUND THEN RAISE EXCEPTION 'CATALOG_MISSING_LOCAL_OUTBOX'; END IF;
 PERFORM 1 FROM catalog_outbox o WHERE o.shard_id=c.shard_id AND o.outbox_id=s.outbox_id
   AND o.payload_bytes=s.result_bytes AND o.payload_sha256=s.result_sha256;
 IF NOT FOUND OR (convert_from(s.result_bytes,'UTF8')::jsonb)->'effects' IS DISTINCT FROM s.effects
   THEN RAISE EXCEPTION 'CATALOG_RESULT_FACT_MISMATCH'; END IF;
 r:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
 key_column:=CASE TG_TABLE_NAME WHEN 'advertiser' THEN 'advertiser_id' WHEN 'ad' THEN 'ad_id' WHEN 'bidword' THEN 'id' ELSE 'creative_id' END;
 row_id:=(r->>key_column)::bigint;
 IF NOT EXISTS(SELECT 1 FROM jsonb_array_elements(s.effects) e WHERE e->>'table'=TG_TABLE_NAME
      AND e->>'operation'=TG_OP AND (e->>'id')::bigint=row_id) THEN RAISE EXCEPTION 'CATALOG_EFFECT_NOT_RECORDED'; END IF;
 RETURN NULL;
END $$;
COMMENT ON FUNCTION catalog_guard_commit() IS '提交前核验实际业务ID与本库同txid事实，错库Outbox不能通过';

CREATE FUNCTION catalog_preserve_fact() RETURNS trigger
LANGUAGE plpgsql SET search_path=public,pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'CATALOG_FACT_DELETE_FORBIDDEN'; END IF;
 IF TG_TABLE_NAME='catalog_outbox' THEN
   IF (to_jsonb(NEW)-ARRAY['state','round','attempts','fence','lease_until','available_at','broker_partition','broker_offset','consumed_by_owner','error_type','published_at'])
      IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','round','attempts','fence','lease_until','available_at','broker_partition','broker_offset','consumed_by_owner','error_type','published_at'])
      THEN RAISE EXCEPTION 'CATALOG_IMMUTABLE_OUTBOX'; END IF;
 ELSIF TG_TABLE_NAME='catalog_step' THEN
   IF OLD.state='APPLIED' AND to_jsonb(NEW) IS DISTINCT FROM to_jsonb(OLD) THEN RAISE EXCEPTION 'CATALOG_IMMUTABLE_RECEIPT'; END IF;
   IF (to_jsonb(NEW)-ARRAY['state','round','attempts','fence','lease_until','available_at','result_bytes','result_sha256','effects','outbox_id','write_txid','error_type','updated_at','ad_id'])
      IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','round','attempts','fence','lease_until','available_at','result_bytes','result_sha256','effects','outbox_id','write_txid','error_type','updated_at','ad_id'])
      OR (OLD.ad_id IS NOT NULL AND NEW.ad_id IS DISTINCT FROM OLD.ad_id) THEN RAISE EXCEPTION 'CATALOG_IMMUTABLE_STEP'; END IF;
 ELSIF TG_TABLE_NAME='catalog_command' THEN
   IF OLD.state IN ('COMPLETED','REJECTED') AND to_jsonb(NEW) IS DISTINCT FROM to_jsonb(OLD) THEN RAISE EXCEPTION 'CATALOG_IMMUTABLE_COMMAND_RESULT'; END IF;
   IF ROW(NEW.shard_id,NEW.source_id,NEW.scope_kind,NEW.scope_id,NEW.command_id,NEW.kind,NEW.request_bytes,NEW.request_sha256,NEW.actor,NEW.reason,NEW.created_at)
      IS DISTINCT FROM ROW(OLD.shard_id,OLD.source_id,OLD.scope_kind,OLD.scope_id,OLD.command_id,OLD.kind,OLD.request_bytes,OLD.request_sha256,OLD.actor,OLD.reason,OLD.created_at)
      THEN RAISE EXCEPTION 'CATALOG_IMMUTABLE_COMMAND'; END IF;
 ELSE
   RAISE EXCEPTION 'CATALOG_IMMUTABLE_IDENTITY';
 END IF;
 RETURN NEW;
END $$;
COMMENT ON FUNCTION catalog_preserve_fact() IS '禁止改写原输入、原结果、原事件字节与开户预留身份；不提供自动清理入口';

CREATE TRIGGER catalog_outbox_immutable BEFORE UPDATE OR DELETE ON catalog_outbox FOR EACH ROW EXECUTE FUNCTION catalog_preserve_fact();
CREATE TRIGGER catalog_step_immutable BEFORE UPDATE OR DELETE ON catalog_step FOR EACH ROW EXECUTE FUNCTION catalog_preserve_fact();
CREATE TRIGGER catalog_command_immutable BEFORE UPDATE OR DELETE ON catalog_command FOR EACH ROW EXECUTE FUNCTION catalog_preserve_fact();
CREATE TRIGGER catalog_identity_immutable BEFORE UPDATE OR DELETE ON catalog_identity FOR EACH ROW EXECUTE FUNCTION catalog_preserve_fact();

CREATE TRIGGER catalog_advertiser_write BEFORE INSERT OR UPDATE OR DELETE ON advertiser FOR EACH ROW EXECUTE FUNCTION catalog_guard_write();
CREATE TRIGGER catalog_advertiser_truncate BEFORE TRUNCATE ON advertiser FOR EACH STATEMENT EXECUTE FUNCTION catalog_guard_write();
CREATE CONSTRAINT TRIGGER catalog_advertiser_commit AFTER INSERT OR UPDATE OR DELETE ON advertiser DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION catalog_guard_commit();

CREATE TRIGGER catalog_ad_write BEFORE INSERT OR UPDATE OR DELETE ON ad FOR EACH ROW EXECUTE FUNCTION catalog_guard_write();
CREATE TRIGGER catalog_ad_truncate BEFORE TRUNCATE ON ad FOR EACH STATEMENT EXECUTE FUNCTION catalog_guard_write();
CREATE CONSTRAINT TRIGGER catalog_ad_commit AFTER INSERT OR UPDATE OR DELETE ON ad DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION catalog_guard_commit();

CREATE TRIGGER catalog_bidword_write BEFORE INSERT OR UPDATE OR DELETE ON bidword FOR EACH ROW EXECUTE FUNCTION catalog_guard_write();
CREATE TRIGGER catalog_bidword_truncate BEFORE TRUNCATE ON bidword FOR EACH STATEMENT EXECUTE FUNCTION catalog_guard_write();
CREATE CONSTRAINT TRIGGER catalog_bidword_commit AFTER INSERT OR UPDATE OR DELETE ON bidword DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION catalog_guard_commit();

CREATE TRIGGER catalog_ad_creative_write BEFORE INSERT OR UPDATE OR DELETE ON ad_creative FOR EACH ROW EXECUTE FUNCTION catalog_guard_write();
CREATE TRIGGER catalog_ad_creative_truncate BEFORE TRUNCATE ON ad_creative FOR EACH STATEMENT EXECUTE FUNCTION catalog_guard_write();
CREATE CONSTRAINT TRIGGER catalog_ad_creative_commit AFTER INSERT OR UPDATE OR DELETE ON ad_creative DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION catalog_guard_commit();

-- 5.5.0不解析FOR UPDATE SKIP LOCKED，保留原PG锁语义并以带shard_id的control查询路由到本库。
CREATE FUNCTION catalog_lock_ready_event_ids(p_shard integer) RETURNS SETOF text
LANGUAGE sql VOLATILE SET search_path=public,pg_catalog AS $$
 SELECT outbox_id FROM catalog_outbox
 WHERE shard_id=p_shard AND purpose='KAFKA' AND state IN ('READY','SENDING')
   AND available_at<=clock_timestamp() AND (lease_until IS NULL OR lease_until<=clock_timestamp())
 ORDER BY available_at,created_at,outbox_id LIMIT 10 FOR UPDATE SKIP LOCKED;
$$;
COMMENT ON FUNCTION catalog_lock_ready_event_ids(integer) IS '真实本库有界跳锁候选，仅封装驱动不支持的语法，不改变发送预算或物理事务';

-- 命令已经PAUSED也必须清算其本库末次步骤，跳过迟到事务持有的行锁避免阻塞整个worker。
CREATE FUNCTION catalog_lock_expired_step_ids(p_shard integer,p_source uuid) RETURNS SETOF text
LANGUAGE sql VOLATILE SET search_path=public,pg_catalog AS $$
 SELECT step_key FROM catalog_step
 WHERE shard_id=p_shard AND source_id=p_source AND state='RUNNING'
   AND attempts=3 AND lease_until<=clock_timestamp()
 ORDER BY lease_until,step_key LIMIT 10 FOR UPDATE SKIP LOCKED;
$$;
COMMENT ON FUNCTION catalog_lock_expired_step_ids(integer,uuid) IS '同源本物理库每轮最多十个末次到期步骤，持锁后条件清算PAUSED且不重置预算或释放占有';

-- 在实际物理库检查业务必需列与不兼容的孩子外键，不能依赖逻辑元数据猜测另一库结构。
CREATE FUNCTION catalog_schema_compatible() RETURNS boolean
LANGUAGE sql STABLE SET search_path=public,pg_catalog AS $$
 SELECT NOT EXISTS(
  SELECT 1 FROM (VALUES
   ('advertiser',ARRAY['advertiser_id','name','daily_budget','status']),
   ('ad',ARRAY['ad_id','advertiser_id','item_id','title','landing_url','quality_score','status','review_status','review_reason','optimization_type','target_cpa','audience_id']),
   ('bidword',ARRAY['id','ad_id','keyword','match_type','bid','bid_mode']),
   ('ad_creative',ARRAY['creative_id','ad_id','title','landing_url','status','review_status'])
  ) expected(table_name,columns),unnest(expected.columns) required(column_name)
  WHERE NOT EXISTS(SELECT 1 FROM pg_attribute a WHERE a.attrelid=to_regclass('public.'||expected.table_name)
    AND a.attname=required.column_name AND a.attnum>0 AND NOT a.attisdropped)
 ) AND NOT EXISTS(SELECT 1 FROM pg_constraint WHERE contype='f'
   AND conrelid IN ('public.bidword'::regclass,'public.ad_creative'::regclass) AND confrelid='public.ad'::regclass);
$$;
COMMENT ON FUNCTION catalog_schema_compatible() IS '两物理库分别检查可靠写必需列及跨owner孩子外键，缺项拒绝启用而不改旧schema';

-- 5.5.0在带/不带冲突目标的ON CONFLICT DO NOTHING均解析失败；原PG唯一键竞争语义保留在本库函数。
CREATE FUNCTION catalog_insert_guard(p_shard integer,p_owner bigint,p_home integer,p_scope text,p_command uuid) RETURNS integer
LANGUAGE plpgsql SET search_path=public,pg_catalog AS $$
DECLARE changed integer;
BEGIN
 INSERT INTO catalog_advertiser_guard(shard_id,advertiser_id,active_command_home,active_command_scope,active_command_id)
 VALUES(p_shard,p_owner,p_home,p_scope,p_command) ON CONFLICT(shard_id,advertiser_id) DO NOTHING;
 GET DIAGNOSTICS changed=ROW_COUNT;
 RETURN changed;
END $$;
COMMENT ON FUNCTION catalog_insert_guard(integer,bigint,integer,text,uuid) IS '显式路由到本库的guard首次登记，唯一键竞争不覆盖原占有者';

-- 封闭后按物理库枚举；不经过逻辑业务表路由而遗漏错库存量。
CREATE FUNCTION catalog_initial_layout(p_shard integer) RETURNS boolean
LANGUAGE sql STABLE SET search_path=public,pg_catalog AS $$
 SELECT NOT EXISTS(SELECT 1 FROM advertiser WHERE advertiser_id<=0 OR advertiser_id%2<>p_shard)
 AND NOT EXISTS(SELECT 1 FROM ad a WHERE a.ad_id<=0 OR a.advertiser_id%2<>p_shard OR NOT EXISTS(SELECT 1 FROM advertiser r WHERE r.advertiser_id=a.advertiser_id))
 AND NOT EXISTS(SELECT 1 FROM bidword WHERE id<=0 OR ad_id<=0 OR ad_id%2<>p_shard)
 AND NOT EXISTS(SELECT 1 FROM ad_creative WHERE creative_id<=0 OR ad_id<=0 OR ad_id%2<>p_shard);
$$;
COMMENT ON FUNCTION catalog_initial_layout(integer) IS '核对真实旧表模数与同库owner，不自动搬正错库数据';

CREATE FUNCTION catalog_initial_page(p_kind text,p_after bigint) RETURNS text
LANGUAGE plpgsql STABLE SET search_path=public,pg_catalog AS $$
DECLARE value jsonb;
BEGIN
 IF p_kind='ROOT' THEN
   SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id),'[]'::jsonb) INTO value FROM
     (SELECT advertiser_id AS id,advertiser_id AS owner FROM advertiser WHERE advertiser_id>p_after ORDER BY advertiser_id LIMIT 100) t;
 ELSIF p_kind='AD' THEN
   SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id),'[]'::jsonb) INTO value FROM
     (SELECT ad_id AS id,advertiser_id AS owner FROM ad WHERE ad_id>p_after ORDER BY ad_id LIMIT 100) t;
 ELSIF p_kind='CHILD' THEN
   SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id),'[]'::jsonb) INTO value FROM
     (SELECT ad_id AS id,0::bigint AS owner FROM (SELECT ad_id FROM bidword WHERE ad_id>p_after UNION SELECT ad_id FROM ad_creative WHERE ad_id>p_after) ids ORDER BY ad_id LIMIT 100) t;
 ELSE RAISE EXCEPTION 'CATALOG_INVALID_INITIAL_PAGE'; END IF;
 RETURN value::text;
END $$;
COMMENT ON FUNCTION catalog_initial_page(text,bigint) IS '封闭集合的稳定最多100项分页；游标不是开放业务的提交水位';

CREATE FUNCTION catalog_freeze_initial_target(p_shard integer,p_command uuid,p_ad bigint,p_owner bigint,p_sha text) RETURNS integer
LANGUAGE plpgsql SET search_path=public,pg_catalog AS $$
DECLARE prior catalog_task_item;
BEGIN
 SELECT * INTO prior FROM catalog_task_item WHERE shard_id=p_shard AND parent_scope='INITIALIZE' AND parent_command_id=p_command AND target_kind='AD' AND target_id=p_ad;
 IF FOUND THEN
   IF prior.target_owner_id<>p_owner OR prior.page_sha256<>p_sha THEN RAISE EXCEPTION 'CATALOG_INITIAL_TARGET_CONFLICT'; END IF;
   RETURN 1;
 END IF;
 INSERT INTO catalog_task_item(shard_id,parent_scope,parent_command_id,ordinal,target_kind,target_id,target_owner_id,expected_version,child_command_id,page_sha256)
 VALUES(p_shard,'INITIALIZE',p_command,p_ad,'AD',p_ad,p_owner,1,p_command,p_sha);
 RETURN 1;
END $$;
COMMENT ON FUNCTION catalog_freeze_initial_target(integer,uuid,bigint,bigint,text) IS '逐页原清单幂等冻结，提交未知只能查回原归属与摘要';

CREATE FUNCTION catalog_receipt_valid(p_step text) RETURNS boolean
LANGUAGE sql STABLE SET search_path=public,pg_catalog AS $$
 SELECT EXISTS(SELECT 1 FROM catalog_step s JOIN catalog_outbox o ON o.shard_id=s.shard_id AND o.outbox_id=s.outbox_id
 WHERE s.step_key=p_step AND s.state='APPLIED' AND s.source_id=o.source_id AND s.command_id=o.command_id
 AND s.command_scope=o.command_scope AND s.step_id=o.step_id AND s.write_txid=o.write_txid
 AND s.result_bytes=o.payload_bytes AND s.result_sha256=o.payload_sha256
 AND (convert_from(s.result_bytes,'UTF8')::jsonb)->'effects'=s.effects);
$$;
COMMENT ON FUNCTION catalog_receipt_valid(text) IS '停止分片显式核验原APPLIED结果、本库同txid片段及实际效果，不伪造或修正历史';

CREATE FUNCTION catalog_statistics() RETURNS text
LANGUAGE sql STABLE SET search_path=public,pg_catalog AS $$
 SELECT coalesce(jsonb_agg(to_jsonb(sample)),'[]'::jsonb)::text FROM (
  SELECT kind,state,count(*)::bigint AS total,
   coalesce(extract(epoch FROM (clock_timestamp()-min(created_at) FILTER(WHERE state IN ('ADMITTED','RUNNING','PAUSED'))))::bigint,0) AS oldest,
   count(*) FILTER(WHERE state='RUNNING' AND lease_until<=clock_timestamp()) AS expired,
   count(*) FILTER(WHERE error_type='RESULT_UNKNOWN') AS unknown FROM catalog_command GROUP BY kind,state
  UNION ALL
  SELECT 'STEP',state,count(*),
   coalesce(extract(epoch FROM (clock_timestamp()-min(created_at) FILTER(WHERE state<>'APPLIED')))::bigint,0),
   count(*) FILTER(WHERE state='RUNNING' AND lease_until<=clock_timestamp()),count(*) FILTER(WHERE error_type='RESULT_UNKNOWN') FROM catalog_step GROUP BY state
  UNION ALL
  SELECT 'EVENT',state,count(*),
   coalesce(extract(epoch FROM (clock_timestamp()-min(created_at) FILTER(WHERE state<>'PUBLISHED')))::bigint,0),
   count(*) FILTER(WHERE state='SENDING' AND lease_until<=clock_timestamp()),count(*) FILTER(WHERE error_type='RESULT_UNKNOWN') FROM catalog_outbox WHERE purpose='KAFKA' GROUP BY state
  UNION ALL
  SELECT 'CONTROL','STOPPED',count(*),0,0,0 FROM catalog_shard_control WHERE stop_reason IS NOT NULL
 ) sample;
$$;
COMMENT ON FUNCTION catalog_statistics() IS '只按有限kind/state聚合积压、年龄、暂停、过期和未知；无广告或命令ID指标标签';
