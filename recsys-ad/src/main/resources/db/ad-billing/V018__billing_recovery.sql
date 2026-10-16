BEGIN;
CREATE TABLE ad_billing_recovery_command (
    command_id uuid PRIMARY KEY,
    advertiser_id bigint NOT NULL CHECK(advertiser_id>0),
    budget_date date NOT NULL,
    family varchar(16) NOT NULL CHECK(family IN ('IMPRESSION','CLICK','CONVERSION')),
    action varchar(16) NOT NULL CHECK(action IN ('REPUBLISH','EXPIRE')),
    target_id uuid NOT NULL,
    reason varchar(256) NOT NULL,
    actor varchar(128) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','REQUEUED','ALREADY_APPLIED','NOT_ELIGIBLE','NEEDS_REVIEW','EXPIRED','ALREADY_TERMINAL','NOT_FOUND')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    CHECK((status='PENDING')=(completed_at IS NULL))
);
COMMENT ON TABLE ad_billing_recovery_command IS 'Sandbox单目标恢复的不可变命令结果及审计检查点';
COMMENT ON COLUMN ad_billing_recovery_command.command_id IS '稳定恢复命令及幂等标识';
COMMENT ON COLUMN ad_billing_recovery_command.advertiser_id IS '归属广告主';
COMMENT ON COLUMN ad_billing_recovery_command.budget_date IS '原预算日';
COMMENT ON COLUMN ad_billing_recovery_command.family IS '固定资金族';
COMMENT ON COLUMN ad_billing_recovery_command.action IS '原事件续发或到期义务恢复';
COMMENT ON COLUMN ad_billing_recovery_command.target_id IS '明确的原事件或资格标识';
COMMENT ON COLUMN ad_billing_recovery_command.reason IS '受控恢复原因';
COMMENT ON COLUMN ad_billing_recovery_command.actor IS '首次可信操作主体';
COMMENT ON COLUMN ad_billing_recovery_command.status IS '不可变首次结果PENDING仅未提交事务内部';
COMMENT ON COLUMN ad_billing_recovery_command.created_at IS '数据库领取命令时间';
COMMENT ON COLUMN ad_billing_recovery_command.completed_at IS '首次事务决定时间';
CREATE FUNCTION ad_billing_recovery_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' OR OLD.status<>'PENDING' THEN RAISE EXCEPTION 'recovery result is immutable'; END IF;
  IF (NEW.command_id,NEW.advertiser_id,NEW.budget_date,NEW.family,NEW.action,NEW.target_id,NEW.reason,NEW.actor,NEW.created_at)
      IS DISTINCT FROM (OLD.command_id,OLD.advertiser_id,OLD.budget_date,OLD.family,OLD.action,OLD.target_id,OLD.reason,OLD.actor,OLD.created_at)
      THEN RAISE EXCEPTION 'recovery input is immutable'; END IF;
  RETURN NEW;
END; $$;
CREATE TRIGGER ad_billing_recovery_guard BEFORE UPDATE OR DELETE ON ad_billing_recovery_command FOR EACH ROW EXECUTE FUNCTION ad_billing_recovery_immutable();
INSERT INTO ad_billing_schema_history(version,description,script_sha256) VALUES(18,'sandbox bounded recovery commands',current_setting('recsys.migration_sha256'));
COMMIT;
