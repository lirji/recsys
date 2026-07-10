-- #2 离线数据平台读侧解耦:offline/数据平台<b>自有</b>的行为读仓,镜像 user_behavior。
-- 由 `sync-behavior-log` 作业从 user_behavior 增量幂等复制(本地脚手架里它是 CDC/事件摄取的替身;
-- 生产应由 Debezium CDC 或 behavior-events 消费者维护)。离线 CF/hot/embedding 作业以 --behavior-table=behavior_log
-- 读它,不再直读行为上下文的 user_behavior(DB-per-service)。SyncBehaviorLogJob 启动时也会防御性自建。
CREATE TABLE IF NOT EXISTS behavior_log (
    id       BIGINT PRIMARY KEY,   -- 沿用 user_behavior.id,幂等 ON CONFLICT
    user_id  BIGINT,
    item_id  BIGINT,
    action   TEXT,
    value    DOUBLE PRECISION,
    scene    TEXT,
    bucket   TEXT,
    ts       TIMESTAMP,
    position INT
);
CREATE INDEX IF NOT EXISTS idx_behavior_log_user_action ON behavior_log(user_id, action);
CREATE INDEX IF NOT EXISTS idx_behavior_log_item ON behavior_log(item_id);
