BEGIN;
-- 查询按账户原预算日约束；复用旧资金表和现有账本索引，不复制第二份权威数据。
CREATE INDEX ad_click_quote_account_idx ON ad_click_quote(advertiser_id,quote_id);
CREATE INDEX ad_click_settlement_day_idx ON ad_click_settlement(budget_date,qualification_id);
CREATE INDEX ad_conversion_settlement_account_idx ON ad_conversion_settlement(advertiser_id,budget_date,qualification_id);
COMMENT ON INDEX ad_click_quote_account_idx IS '账户日对账先按广告主定位不可变点击报价';
COMMENT ON INDEX ad_click_settlement_day_idx IS '点击义务按原预算日关联报价进行核查';
COMMENT ON INDEX ad_conversion_settlement_account_idx IS '转化义务按广告主及原预算日核查当前状态';
INSERT INTO ad_billing_schema_history(version,description,script_sha256)
VALUES(16,'sandbox account day reconciliation indexes',current_setting('recsys.migration_sha256'));
COMMIT;
