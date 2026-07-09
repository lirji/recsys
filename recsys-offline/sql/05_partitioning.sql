-- ============================================================
-- 日志表分区 + 覆盖索引(S7,docs/06 §2)
-- user_behavior / ad_event 是无界增长的日志表:99% 是冷数据,却全在在线主表拖慢查询与 vacuum。
-- 本文件两部分:
--   A. 覆盖索引 —— 幂等、可对既有表直接执行,立竿见影(报表/校准的 DISTINCT 走 index-only)。
--   B. 按 ts 月度 RANGE 分区的迁移模板 —— 需重建表(声明式分区的主键须含分区键),
--      在数据量真正变大时执行;冷分区可 DETACH 归档,热分区留在线。
-- ============================================================

-- ---------- A. 覆盖索引(立即可用,幂等)----------
-- ad-report / ad-calibrate 反复跑 SELECT DISTINCT request_id, ad_id FROM ad_event WHERE event_type='CLICK';
-- 现 idx(event_type,ts) 命中 event_type 后仍回堆取 request_id/ad_id → 加覆盖索引走 index-only。
CREATE INDEX IF NOT EXISTS idx_ad_event_type_req_ad
    ON ad_event (event_type, request_id, ad_id);
-- ad-explore-stats 按 (ad_id, creative_id) 聚合(creative_id 是后加列,无索引)。
CREATE INDEX IF NOT EXISTS idx_ad_event_ad_creative
    ON ad_event (ad_id, creative_id);

-- ---------- B. 月度 RANGE 分区迁移模板(数据量大时执行;下面以 user_behavior 为例)----------
-- ⚠️ 非幂等、需停写窗口:声明式分区表的主键/唯一约束必须包含分区键(ts),故 PK 由 (id) 改为 (id, ts)。
-- 步骤(在事务里做,失败可回滚):
--
-- BEGIN;
-- -- 1. 旧表改名留存
-- ALTER TABLE user_behavior RENAME TO user_behavior_legacy;
-- -- 2. 建分区父表(列同旧表;PK 含分区键 ts;position 生成列/tsv 等按需)
-- CREATE TABLE user_behavior (
--     id       BIGINT GENERATED ALWAYS AS IDENTITY,
--     user_id  BIGINT,
--     item_id  BIGINT,
--     action   TEXT,
--     value    DOUBLE PRECISION,
--     scene    TEXT,
--     bucket   TEXT,
--     position INT,
--     ts       TIMESTAMP NOT NULL DEFAULT now(),
--     PRIMARY KEY (id, ts)
-- ) PARTITION BY RANGE (ts);
-- -- 3. 建月度分区 + 兜底默认分区(把落在任何显式分区外的行接住,避免插入失败)
-- CREATE TABLE user_behavior_2026_06 PARTITION OF user_behavior
--     FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
-- CREATE TABLE user_behavior_2026_07 PARTITION OF user_behavior
--     FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
-- CREATE TABLE user_behavior_default PARTITION OF user_behavior DEFAULT;
-- -- 4. 索引建在父表上(自动下推到各分区)
-- CREATE INDEX ON user_behavior (user_id, ts);
-- CREATE INDEX ON user_behavior (item_id, ts);
-- CREATE INDEX ON user_behavior (user_id, action, item_id);
-- -- 5. 回灌数据(overriding 让 IDENTITY 接受旧 id)
-- INSERT INTO user_behavior OVERRIDING SYSTEM VALUE SELECT * FROM user_behavior_legacy;
-- -- 6. 校验行数一致后删旧表
-- -- SELECT (SELECT count(*) FROM user_behavior) = (SELECT count(*) FROM user_behavior_legacy);
-- DROP TABLE user_behavior_legacy;
-- COMMIT;
--
-- 运维:
--   * 每月建下月分区(可用 pg_partman 自动化,或 @Scheduled 作业提前建);
--   * 冷数据归档:ALTER TABLE user_behavior DETACH PARTITION user_behavior_2026_01;
--     再 COPY 到 Parquet/对象存储供离线训练,然后 DROP;热分区(近 3~6 月)留在线。
--   * ad_event 同法(按 ts 分区),它经 !SINGLE 恒落 ds_0、是广告链路最大表,分区收益最明显。
