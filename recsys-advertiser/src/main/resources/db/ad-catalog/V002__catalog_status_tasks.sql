-- 已发布V001保持原文；本扩展仅增加任务预算与不可变清单保护，不改变业务路由或既有事实。
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM advertiser_catalog_schema_history
   WHERE shard_id=current_setting('recsys.catalog.install_shard')::smallint AND version=1)
   OR length(coalesce(current_setting('recsys.catalog.install_sha256',true),''))<>64 THEN
   RAISE EXCEPTION 'CATALOG_V001_REQUIRED';
 END IF;
END $$;

ALTER TABLE catalog_task_item ADD COLUMN phase text NOT NULL DEFAULT 'ADMIT';
ALTER TABLE catalog_task_item ADD COLUMN round integer NOT NULL DEFAULT 1 CHECK(round>0);
ALTER TABLE catalog_task_item ADD COLUMN attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 3);
ALTER TABLE catalog_task_item ADD COLUMN available_at timestamptz NOT NULL DEFAULT clock_timestamp();
COMMENT ON COLUMN catalog_task_item.phase IS '逐目标派发或等待原子命令结果的阶段，成功观察才推进';
COMMENT ON COLUMN catalog_task_item.round IS '逐目标审计恢复轮次，重启不得清零';
COMMENT ON COLUMN catalog_task_item.attempts IS '本阶段连续未知尝试预算，外部查询或派发前持久计次';
COMMENT ON COLUMN catalog_task_item.available_at IS '本目标下次可观察时间，失败退避不阻塞其他广告主';
CREATE INDEX catalog_task_fair ON catalog_task_item(shard_id,parent_scope,parent_command_id,state,available_at,updated_at,ordinal);

CREATE FUNCTION catalog_preserve_status_target() RETURNS trigger
LANGUAGE plpgsql SET search_path=public,pg_catalog AS $$
BEGIN
 -- 只约束本片两类清单，不改写V001初始化协议或已发布输入。
 IF OLD.target_kind NOT IN ('FANOUT_AD','STATUS_OWNER') THEN RETURN coalesce(NEW,OLD); END IF;
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'CATALOG_TARGET_DELETE_FORBIDDEN'; END IF;
 IF (to_jsonb(NEW)-ARRAY['state','result_bytes','error_type','updated_at','phase','round','attempts','available_at'])
    IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','result_bytes','error_type','updated_at','phase','round','attempts','available_at'])
    THEN RAISE EXCEPTION 'CATALOG_IMMUTABLE_TARGET'; END IF;
 IF OLD.state IN ('COMPLETED','CONFLICT','CANCELLED') AND to_jsonb(NEW) IS DISTINCT FROM to_jsonb(OLD)
    THEN RAISE EXCEPTION 'CATALOG_IMMUTABLE_TARGET_RESULT'; END IF;
 IF NEW.state='CANCELLED' AND OLD.state<>'READY' THEN RAISE EXCEPTION 'CATALOG_DISPATCHED_NOT_CANCELLABLE'; END IF;
 RETURN NEW;
END $$;
COMMENT ON FUNCTION catalog_preserve_status_target() IS '冻结广告目标、预期版本、派生子命令和页摘要；只允许取消未派发项，终态原结果不可改';
CREATE TRIGGER catalog_status_target_immutable BEFORE UPDATE OR DELETE ON catalog_task_item
 FOR EACH ROW EXECUTE FUNCTION catalog_preserve_status_target();

INSERT INTO advertiser_catalog_schema_history(shard_id,version,description,script_sha256)
 VALUES(current_setting('recsys.catalog.install_shard')::smallint,2,'目录广告主状态与跨owner逐目标任务',current_setting('recsys.catalog.install_sha256'));
