package com.recsys.ad.billing;

import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.BillingReconciliation.*;
import com.recsys.common.ad.delivery.SandboxDelivery.ChargeMode;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

/** 只读对账SQL集中于资金owner；表名只来自服务端封闭枚举，查询值全部绑定。 */
public class ReconciliationRepository {
    public static final String MIGRATION="db/ad-billing/V016__reconciliation_indexes.sql";
    private final JdbcTemplate jdbc;
    public ReconciliationRepository(JdbcTemplate jdbc) {
        this.jdbc=new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(5);
    }
    /** 比较事务资源身份，不把同URL的另一个连接池误认为同一事务。 */
    public javax.sql.DataSource dataSource() {return jdbc.getDataSource();}
    /** 新查询必须能完整读取三族事实；启动不自动补表或把缺失模式解释为0。 */
    public void verifySchema() {
        var migrations=Map.ofEntries(Map.entry(1,BudgetRepository.MIGRATION),Map.entry(3,ReservationRepository.MIGRATION),
                Map.entry(5,ImpressionReceiptRepository.MIGRATION),Map.entry(6,BillingOutboxRepository.MIGRATION),
                Map.entry(8,ClickQuoteRepository.MIGRATION),Map.entry(9,ClickSettlementRepository.MIGRATION),
                Map.entry(10,"db/ad-billing/V010__click_event_delivery.sql"),Map.entry(12,ConversionQuoteRepository.MIGRATION),
                Map.entry(13,ConversionSettlementRepository.MIGRATION),Map.entry(14,"db/ad-billing/V014__conversion_event_delivery.sql"),Map.entry(16,MIGRATION));
        migrations.forEach((version,path)->{
            var installed=jdbc.queryForObject("SELECT script_sha256 FROM ad_billing_schema_history WHERE version=?",String.class,version);
            if(!BudgetRepository.migrationSha256(path).equals(installed))throw new IllegalStateException("对账所需adDb迁移缺失或漂移");
        });
        // 历史摘要存在也不能代替实际表可读；LIMIT 0只检查结构，不扫描账户数据。
        for(var family:ChargeMode.values()) {
            var t=tables(family);
            jdbc.queryForList("SELECT ledger_id,qualification_id,advertiser_id,budget_date,amount FROM "+t.prefix+"_ledger LIMIT 0");
            jdbc.queryForList("SELECT event_id,delivery_state,created_at FROM "+t.prefix+"_outbox LIMIT 0");
            jdbc.queryForList("SELECT event_id,qualification_id,advertiser_id,budget_date,amount,applied_at FROM "+t.prefix+"_projection_fact LIMIT 0");
            jdbc.queryForList("SELECT advertiser_id,budget_date,charged_amount,"+t.countColumn+" FROM "+t.prefix+"_projection_daily LIMIT 0");
            jdbc.queryForList("WITH scope AS (SELECT 0::bigint advertiser_id,DATE '1970-01-01' budget_date) "+obligations(family)+" LIMIT 0");
        }
    }
    /** 首次查询建立同事务MVCC快照；后续所有统计共享它，时间仅用于本次到期判读。 */
    public Instant observedAt() {return jdbc.queryForObject("SELECT transaction_timestamp()",OffsetDateTime.class).toInstant();}
    /** 不锁账户；不存在与查询失败明确区分。 */
    public Optional<BudgetAccount> account(Scope scope) {
        return jdbc.query("""
                SELECT * FROM ad_budget_account WHERE traffic_class='SANDBOX' AND currency='CNY' AND advertiser_id=? AND budget_date=?
                """,(rs,n)->new BudgetAccount(rs.getLong("advertiser_id"),scope.budgetDate(),rs.getBigDecimal("limit_amount"),
                rs.getBigDecimal("held_amount"),rs.getBigDecimal("charged_amount"),rs.getLong("version")),scope.advertiserId(),scope.budgetDate()).stream().findFirst();
    }
    /** 逐事件比对关键身份与金额；零元事件通过笔数/ID发现缺口，不能只看sum。 */
    public FamilySummary family(Scope scope,ChargeMode family,Instant observedAt) {
        var t=tables(family);
        String query="""
                WITH scope AS (SELECT ?::bigint advertiser_id,?::date budget_date,?::timestamptz observed_at),
                obligations AS (%s),
                ledger AS (SELECT l.* FROM %s_ledger l,scope p WHERE l.advertiser_id=p.advertiser_id AND l.budget_date=p.budget_date),
                facts AS (SELECT f.* FROM %s_projection_fact f,scope p WHERE f.advertiser_id=p.advertiser_id AND f.budget_date=p.budget_date),
                daily AS (SELECT d.* FROM %s_projection_daily d,scope p WHERE d.advertiser_id=p.advertiser_id AND d.budget_date=p.budget_date)
                SELECT
                  (SELECT count(*) FROM obligations WHERE state='HELD') held_count,
                  (SELECT coalesce(sum(amount),0) FROM obligations WHERE state='HELD') held_amount,
                  (SELECT count(*) FROM obligations,scope p WHERE state='HELD' AND expires_at<=p.observed_at) expired_held_count,
                  (SELECT count(*) FROM ledger) ledger_count,(SELECT coalesce(sum(amount),0) FROM ledger) ledger_amount,
                  (SELECT count(*) FROM obligations o LEFT JOIN ledger l ON l.qualification_id=o.qualification_id
                    WHERE o.state='CAPTURED' AND l.ledger_id IS NULL) missing_ledger_count,
                  (SELECT count(*) FROM ledger l LEFT JOIN obligations o ON l.qualification_id=o.qualification_id
                    WHERE o.qualification_id IS NULL OR o.state<>'CAPTURED' OR o.amount<>l.amount
                      OR (%s AND o.ledger_id IS DISTINCT FROM l.ledger_id)) invalid_ledger_count,
                  (SELECT count(*) FROM ledger l JOIN %s_outbox o ON o.event_id=l.ledger_id WHERE o.delivery_state IN ('READY','SENDING')) pending_outbox_count,
                  (SELECT count(*) FROM ledger l JOIN %s_outbox o ON o.event_id=l.ledger_id WHERE o.delivery_state='DEAD') dead_outbox_count,
                  (SELECT count(*) FROM ledger l LEFT JOIN %s_outbox o ON o.event_id=l.ledger_id WHERE o.event_id IS NULL) missing_outbox_count,
                  (SELECT min(o.created_at) FROM ledger l JOIN %s_outbox o ON o.event_id=l.ledger_id WHERE o.delivery_state IN ('READY','SENDING')) oldest_pending_at,
                  (SELECT count(*) FROM facts) fact_count,(SELECT coalesce(sum(amount),0) FROM facts) fact_amount,
                  (SELECT count(*) FROM ledger l LEFT JOIN %s_projection_fact f ON f.event_id=l.ledger_id WHERE f.event_id IS NULL) missing_fact_count,
                  (SELECT count(*) FROM ledger l JOIN %s_projection_fact f ON f.event_id=l.ledger_id WHERE f.qualification_id<>l.qualification_id
                    OR f.advertiser_id<>l.advertiser_id OR f.budget_date<>l.budget_date OR f.amount<>l.amount) invalid_fact_count,
                  (SELECT count(*) FROM facts f LEFT JOIN ledger l ON f.event_id=l.ledger_id WHERE l.ledger_id IS NULL) unexpected_fact_count,
                  (SELECT max(applied_at) FROM facts) last_applied_at,
                  EXISTS(SELECT 1 FROM daily) summary_present,
                  coalesce((SELECT %s FROM daily),0) summary_count,coalesce((SELECT charged_amount FROM daily),0) summary_amount
                """.formatted(obligations(family),t.prefix,t.prefix,t.prefix,family!=ChargeMode.IMPRESSION,
                t.prefix,t.prefix,t.prefix,t.prefix,t.prefix,t.prefix,t.countColumn);
        return jdbc.queryForObject(query,(rs,n)->new FamilySummary(family,rs.getLong("held_count"),amount(rs,"held_amount"),rs.getLong("expired_held_count"),
                rs.getLong("ledger_count"),amount(rs,"ledger_amount"),rs.getLong("missing_ledger_count"),rs.getLong("invalid_ledger_count"),
                rs.getLong("pending_outbox_count"),rs.getLong("dead_outbox_count"),rs.getLong("missing_outbox_count"),instant(rs,"oldest_pending_at"),
                rs.getLong("fact_count"),amount(rs,"fact_amount"),rs.getLong("missing_fact_count"),rs.getLong("invalid_fact_count"),rs.getLong("unexpected_fact_count"),
                instant(rs,"last_applied_at"),rs.getBoolean("summary_present"),rs.getLong("summary_count"),amount(rs,"summary_amount")),
                scope.advertiserId(),scope.budgetDate(),observedAt.atOffset(java.time.ZoneOffset.UTC));
    }
    private String obligations(ChargeMode family) {
        return switch(family) {
            case IMPRESSION -> """
                    SELECT r.reservation_id qualification_id,r.amount,r.expires_at,r.state,NULL::uuid ledger_id
                    FROM ad_budget_reservation r,scope p WHERE r.advertiser_id=p.advertiser_id AND r.budget_date=p.budget_date
                    """;
            case CLICK -> """
                    SELECT s.qualification_id,q.amount,q.expires_at,s.state,s.ledger_id
                    FROM ad_click_settlement s JOIN ad_click_quote q ON q.quote_id=s.qualification_id,scope p
                    WHERE q.advertiser_id=p.advertiser_id AND s.budget_date=p.budget_date
                    """;
            case CONVERSION -> """
                    SELECT s.qualification_id,q.amount,q.expires_at,s.state,s.ledger_id
                    FROM ad_conversion_settlement s JOIN ad_conversion_quote q ON q.quote_id=s.qualification_id,scope p
                    WHERE s.advertiser_id=p.advertiser_id AND s.budget_date=p.budget_date
                    """;
        };
    }
    private record Tables(String prefix,String countColumn) {}
    private Tables tables(ChargeMode family) {
        return switch(family) {
            case IMPRESSION->new Tables("ad_billing","impression_count");
            case CLICK->new Tables("ad_click","click_count");
            case CONVERSION->new Tables("ad_conversion","conversion_count");
        };
    }
    private static String amount(ResultSet rs,String column)throws SQLException {return BillingReconciliation.decimal(rs.getBigDecimal(column));}
    private static Instant instant(ResultSet rs,String column)throws SQLException {
        var value=rs.getObject(column,OffsetDateTime.class);return value==null?null:value.toInstant();
    }
}
