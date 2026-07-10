-- #3 物理拆库(ad-serving 上下文):ad_event 的数据平台**自有读仓** ad_event_log。
-- ad-serving 权威 ad_event 搬到自有库(见 13_ad_serving_db.sql)后,离线分析作业(ad-report/calibrate/ocpc/
-- delay/explore-stats/quality/attribution/gen-ad-cvr/data-quality)不再直读 ad-serving 的 ad_event,改读本读仓。
-- 由离线作业 sync-ad-event-log 从权威 ad_event(同库或 --source-db 跨库)增量幂等同步(CDC 替身)。
-- 读切换开关:分析作业 --ad-event-table=ad_event_log(默认 ad_event,行为不变)。
--
-- 与 02_ad_schema.sql 的 ad_event 同构(16 列,含 id 主键供 watermark 增量);追加日志故 ON CONFLICT (id) DO NOTHING。
CREATE TABLE IF NOT EXISTS ad_event_log (
    id            BIGINT PRIMARY KEY,   -- 携带 ad_event.id,幂等 ON CONFLICT
    request_id    TEXT,
    query         TEXT,
    user_id       BIGINT,
    ad_id         BIGINT,
    bidword_id    BIGINT,
    position      INT,
    event_type    TEXT,
    pctr          DOUBLE PRECISION,
    pctr_calib    DOUBLE PRECISION,
    ecpm          DOUBLE PRECISION,
    charged_price DOUBLE PRECISION,
    relevance     DOUBLE PRECISION,
    ad_bucket     TEXT,
    ts            TIMESTAMP,
    creative_id   BIGINT
);
CREATE INDEX IF NOT EXISTS idx_ad_event_log_type_ts ON ad_event_log (event_type, ts);
CREATE INDEX IF NOT EXISTS idx_ad_event_log_ad ON ad_event_log (ad_id, event_type);
CREATE INDEX IF NOT EXISTS idx_ad_event_log_req ON ad_event_log (request_id);
