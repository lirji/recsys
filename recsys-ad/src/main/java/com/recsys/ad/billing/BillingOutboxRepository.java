package com.recsys.ad.billing;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Optional;
import java.util.UUID;

/** 每条语句独立原子提交；领取之后不持有数据库锁等待 broker。 */
public class BillingOutboxRepository {
    public static final String MIGRATION = "db/ad-billing/V006__billing_event_delivery.sql";
    public static final int MAX_ATTEMPTS = 20;
    public static final int LEASE_SECONDS = 30;
    /** 仅允许两组服务端固定表族，不接受外部表名或SQL片段。 */
    public enum EventFamily {
        IMPRESSION("ad_billing",6,MIGRATION,"ad.billing"),
        CLICK("ad_click",10,"db/ad-billing/V010__click_event_delivery.sql","ad.billing.click"),
        CONVERSION("ad_conversion",14,"db/ad-billing/V014__conversion_event_delivery.sql","ad.billing.conversion");
        private final String prefix;
        private final int version;
        private final String migration;
        private final String metrics;
        EventFamily(String prefix,int version,String migration,String metrics) {
            this.prefix=prefix;this.version=version;this.migration=migration;this.metrics=metrics;
        }
        /** 指标按有限业务族分开，不添加事件或用户ID标签。 */
        public String metrics() { return metrics; }
    }
    private final JdbcTemplate jdbc;
    private final EventFamily family;
    public BillingOutboxRepository(JdbcTemplate jdbc) { this(jdbc,EventFamily.IMPRESSION); }
    /** 点击与CPA复用可靠投递算法，独立表族和迁移仍由明确owner配置。 */
    public BillingOutboxRepository(JdbcTemplate jdbc,EventFamily family) {
        this.family=java.util.Objects.requireNonNull(family);
        // 专属模板限制单条领取/标记的数据库等待，不改变宿主共享模板。
        this.jdbc=new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(5);
    }
    public record Pending(UUID eventId, String destination, String key, String payload, long fence, int attempts) {}
    public record Backlog(long pending, long dead, double oldestSeconds) {}

    /** 只校验迁移，不能在应用启动时隐式改共享数据库。 */
    public void verifySchema() {
        if (!BudgetRepository.migrationSha256(family.migration).equals(jdbc.queryForObject(
                "SELECT script_sha256 FROM ad_billing_schema_history WHERE version=?", String.class,family.version))) {
            throw new IllegalStateException("adDb 事件投递迁移缺失或漂移");
        }
        jdbc.queryForList(sql("SELECT delivery_state, available_at, fence FROM ad_billing_outbox LIMIT 0"));
        jdbc.queryForList(sql("SELECT payload_hash FROM ad_billing_projection_fact LIMIT 0"));
        jdbc.queryForList(sql("SELECT charged_amount FROM ad_billing_projection_daily LIMIT 0"));
    }

    /** 崩溃也耗用一次领取；有界扫出耗尽租约，避免最后一次崩溃永久卡在 SENDING。 */
    public int exhaust() {
        return jdbc.update(sql("""
                WITH due AS (SELECT event_id FROM ad_billing_outbox
                    WHERE delivery_state IN ('READY','SENDING') AND available_at<=clock_timestamp() AND attempts>=?
                    ORDER BY available_at,event_id LIMIT 100 FOR UPDATE SKIP LOCKED)
                UPDATE ad_billing_outbox o SET delivery_state='DEAD',last_error='ATTEMPTS_EXHAUSTED'
                FROM due WHERE o.event_id=due.event_id
                """), MAX_ATTEMPTS);
    }

    /** 原子领取单条；多个进程不会同时拿到同一 fence。 */
    public Optional<Pending> claim() {
        return jdbc.query(sql("""
                WITH due AS (SELECT event_id FROM ad_billing_outbox
                    WHERE delivery_state IN ('READY','SENDING') AND available_at<=clock_timestamp() AND attempts<?
                    ORDER BY available_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED)
                UPDATE ad_billing_outbox o SET delivery_state='SENDING',attempts=attempts+1,fence=fence+1,
                    available_at=clock_timestamp()+(? * interval '1 second')
                FROM due WHERE o.event_id=due.event_id
                RETURNING o.event_id,o.destination,o.partition_key,o.payload_json,o.fence,o.attempts
                """), (rs,n)->new Pending(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getInt(6)),
                MAX_ATTEMPTS,LEASE_SECONDS).stream().findFirst();
    }

    /** broker 确认只证明送达；必须仍持有效租约才标记，否则交给新执行器重发。 */
    public boolean published(Pending event) {
        return jdbc.update(sql("""
                UPDATE ad_billing_outbox SET delivery_state='PUBLISHED',published_at=clock_timestamp(),last_error=NULL
                WHERE event_id=? AND fence=? AND delivery_state='SENDING' AND available_at>clock_timestamp()
                """),event.eventId(),event.fence())==1;
    }

    /** 不保存异常消息/堆栈，退避和最大次数持久化，重启不会清零。 */
    public boolean failed(Pending event, long delayMs, String errorType) {
        if (delayMs<0 || delayMs>60000 || errorType==null || !errorType.matches("[A-Za-z0-9_]{1,80}")) {
            throw new IllegalArgumentException("无效重试参数");
        }
        return jdbc.update(sql("""
                UPDATE ad_billing_outbox SET delivery_state=CASE WHEN attempts>=? THEN 'DEAD' ELSE 'READY' END,
                    available_at=clock_timestamp()+(? * interval '1 millisecond'),last_error=?
                WHERE event_id=? AND fence=? AND delivery_state='SENDING' AND available_at>clock_timestamp()
                """),MAX_ATTEMPTS,delayMs,errorType,event.eventId(),event.fence())==1;
    }

    /** 受控恢复工具使用：仅重开 DEAD，保留原事件并同语句写审计；重复操作无新效果。 */
    public boolean replay(UUID replayId, UUID eventId, String actor, String reason) {
        if (replayId==null || eventId==null || actor==null || actor.isBlank() || actor.length()>200
                || reason==null || reason.isBlank() || reason.length()>500) throw new IllegalArgumentException("重放须有操作者与原因");
        return jdbc.update(sql("""
                WITH locked AS (SELECT event_id,attempts FROM ad_billing_outbox
                    WHERE event_id=? AND delivery_state='DEAD' FOR UPDATE),
                changed AS (UPDATE ad_billing_outbox o SET delivery_state='READY',attempts=0,fence=fence+1,
                    available_at=clock_timestamp(),last_error=NULL FROM locked l WHERE o.event_id=l.event_id
                    RETURNING o.event_id,l.attempts)
                INSERT INTO ad_billing_outbox_replay(replay_id,event_id,actor,reason,previous_attempts)
                SELECT ?,event_id,?,?,attempts FROM changed
                """),eventId,replayId,actor,reason)==1;
    }

    /** 仅在启用 relay 的低频 tick 观测；无高基数标签，运维据此定位积压。 */
    public Backlog backlog() {
        return jdbc.queryForObject(sql("""
                SELECT count(*) FILTER (WHERE delivery_state IN ('READY','SENDING')),
                    count(*) FILTER (WHERE delivery_state='DEAD'),
                    coalesce(extract(epoch FROM clock_timestamp()-min(created_at)
                        FILTER (WHERE delivery_state IN ('READY','SENDING'))),0)
                FROM ad_billing_outbox WHERE delivery_state<>'PUBLISHED'
                """),(rs,n)->new Backlog(rs.getLong(1),rs.getLong(2),rs.getDouble(3)));
    }
    private String sql(String statement) {
        // 只替换当前类中审查过的固定命名空间；所有业务值仍由JDBC绑定。
        return family==EventFamily.IMPRESSION?statement:statement.replace("ad_billing_",family.prefix+"_");
    }

}
