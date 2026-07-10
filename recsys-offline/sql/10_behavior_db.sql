-- #3 物理拆库(behavior 首刀):把行为上下文的 user_behavior 从共享 recsys 库搬到 behavior 自有库 recsys_behavior。
-- 本地用同一 PG 实例内的独立 database 演示 database-per-service(与 ShardingSphere 的 recsys/recsys_ds1 同理);
-- 生产则是独立实例。此文件是**手动迁移脚本**(不进 docker initdb 自动执行,因为要新建 database)。
--
-- 迁移步骤(在 psql 里执行):
--   1) 建库 + 表:
--        CREATE DATABASE recsys_behavior;
--        \c recsys_behavior
--        CREATE TABLE user_behavior (LIKE recsys.public.user_behavior INCLUDING ALL);   -- 若跨库 LIKE 不可用见下
--   2) 迁数据(同实例跨库无法直接 INSERT...SELECT,用 pg_dump 管道):
--        pg_dump -t public.user_behavior --data-only recsys | psql recsys_behavior
--   3) 应用切换:behavior 服务设 BEHAVIOR_PG_DB=recsys_behavior(写自有库);
--      离线 sync-behavior-log 加 --source-db=recsys_behavior(跨库 CDC 式复制到主库 behavior_log);
--      rec-engine 已看过滤设 recsys.behavior.seen-source=replica(读 seen:{user} 事件读模型,不再读 user_behavior)。
--   4) 校验无跨库读者后,可从 recsys 库 DROP TABLE user_behavior(本首刀不删,保留回滚)。
--
-- 下面是 recsys_behavior 里 user_behavior 的表结构(与 01_schema.sql 一致),供步骤 1 直接用:
-- \c recsys_behavior
CREATE TABLE IF NOT EXISTS user_behavior (
    id       BIGSERIAL PRIMARY KEY,
    user_id  BIGINT,
    item_id  BIGINT,
    action   TEXT,
    value    DOUBLE PRECISION,
    scene    TEXT,
    bucket   TEXT,
    ts       TIMESTAMP DEFAULT now(),
    position INT
);
CREATE INDEX IF NOT EXISTS idx_behavior_user ON user_behavior(user_id);
CREATE INDEX IF NOT EXISTS idx_behavior_item ON user_behavior(item_id);
