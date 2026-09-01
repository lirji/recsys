-- 曝光级 CLICK 幂等版本化迁移（存量 pgdata 必须在发布新 rec-engine/behavior 前执行）。
-- 可重复执行；先加 nullable 列，再建部分唯一索引，不影响旧客户端 exposure_id=null 数据。
BEGIN;

ALTER TABLE user_behavior ADD COLUMN IF NOT EXISTS exposure_id TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS uq_behavior_click_exposure
    ON user_behavior(exposure_id) WHERE action='CLICK' AND exposure_id IS NOT NULL;

DO $$
BEGIN
    IF to_regclass('public.behavior_log') IS NOT NULL THEN
        ALTER TABLE behavior_log ADD COLUMN IF NOT EXISTS exposure_id TEXT;
    END IF;
END $$;

COMMIT;
