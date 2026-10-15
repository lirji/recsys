CREATE TABLE IF NOT EXISTS behavior_log (
    id BIGINT PRIMARY KEY, user_id BIGINT, item_id BIGINT, action TEXT,
    value DOUBLE PRECISION, scene TEXT, bucket TEXT, ts TIMESTAMP, position INT,
    exposure_id TEXT);
COMMENT ON TABLE behavior_log IS '供离线数据平台使用的用户行为事件读仓';
ALTER TABLE behavior_log ADD COLUMN IF NOT EXISTS exposure_id TEXT;
CREATE INDEX IF NOT EXISTS idx_behavior_log_user_action ON behavior_log(user_id, action);
CREATE INDEX IF NOT EXISTS idx_behavior_log_item ON behavior_log(item_id);
COMMENT ON COLUMN behavior_log.id IS '源行为ID及原增量复制水位';
COMMENT ON COLUMN behavior_log.user_id IS '原用户身份，可空';
COMMENT ON COLUMN behavior_log.item_id IS '原物品身份，可空';
COMMENT ON COLUMN behavior_log.action IS '原行为类型';
COMMENT ON COLUMN behavior_log.value IS '原行为值，可空';
COMMENT ON COLUMN behavior_log.scene IS '原业务场景';
COMMENT ON COLUMN behavior_log.bucket IS '原实验桶，可空';
COMMENT ON COLUMN behavior_log.ts IS '源行为发生时间，保留原TIMESTAMP类型';
COMMENT ON COLUMN behavior_log.position IS '原展示位置，可空';
COMMENT ON COLUMN behavior_log.exposure_id IS '源曝光标识，可空';
