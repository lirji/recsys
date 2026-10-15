BEGIN;
-- 频控只读已接受事实；分别按广告和广告主定位，LIMIT 在达到准入上限后停止计数。
CREATE INDEX ad_ledger_user_ad_time_idx ON ad_billing_ledger(traffic_class,user_id,ad_id,accepted_at);
CREATE INDEX ad_ledger_user_advertiser_time_idx ON ad_billing_ledger(traffic_class,user_id,advertiser_id,accepted_at);
COMMENT ON INDEX ad_ledger_user_ad_time_idx IS 'sandbox 同用户同广告当日已接受次数准入查询';
COMMENT ON INDEX ad_ledger_user_advertiser_time_idx IS 'sandbox 同用户同广告主当日已接受次数准入查询';
INSERT INTO ad_billing_schema_history(version,description,script_sha256)
VALUES (7,'sandbox feed accepted frequency',current_setting('recsys.migration_sha256'));
COMMIT;
