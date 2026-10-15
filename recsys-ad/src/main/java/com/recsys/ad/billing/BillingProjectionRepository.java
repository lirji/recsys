package com.recsys.ad.billing;

import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.UUID;
import com.recsys.common.ad.billing.CnyAmount;

/** 消费者自己的可重建投影，不连接权威账户/账本，不以版本水位丢弃独立金额。 */
public class BillingProjectionRepository {
    private final JdbcTemplate jdbc;
    public BillingProjectionRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public javax.sql.DataSource dataSource() { return jdbc.getDataSource(); }

    /** Inbox 唯一键与汇总由调用方同一事务包围；同 ID 改负载必须失败。 */
    public boolean insert(BillingEventCodec.Validated value) {
        var e=value.event();
        int rows=jdbc.update("""
                INSERT INTO ad_billing_projection_fact(event_id,qualification_id,receipt_id,advertiser_id,budget_date,amount,payload_hash,payload_json)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """,UUID.fromString(e.eventId()),UUID.fromString(e.qualificationId()),UUID.fromString(e.receiptId()),
                Long.parseLong(e.advertiserId()),LocalDate.parse(e.budgetDate()),CnyAmount.parse(e.amount()),value.hash(),value.canonical());
        if (rows==1) return true;
        // 同一事件并发可能先命中资格/回执唯一键，不能只指定 event_id 冲突目标。
        // 冲突后必须找到同 eventId 同负载，换 eventId 复用资格仍严格拒绝。
        var original=jdbc.query("SELECT payload_hash FROM ad_billing_projection_fact WHERE event_id=?",
                (rs,n)->rs.getString(1),UUID.fromString(e.eventId()));
        if (original.size()!=1 || !value.hash().equals(original.getFirst())) {
            throw new IllegalStateException("账本事件身份或负载冲突");
        }
        return false;
    }

    /** 即使账户版本较小，只要是首次独立事实也累加；精度由 NUMERIC 保证。 */
    public void accumulate(BillingEventCodec.Validated value) {
        var e=value.event();
        if (jdbc.update("""
                INSERT INTO ad_billing_projection_daily(advertiser_id,budget_date,impression_count,charged_amount)
                VALUES (?,?,1,?)
                ON CONFLICT (advertiser_id,budget_date) DO UPDATE SET
                    impression_count=ad_billing_projection_daily.impression_count+1,
                    charged_amount=ad_billing_projection_daily.charged_amount+excluded.charged_amount,
                    updated_at=clock_timestamp()
                """,Long.parseLong(e.advertiserId()),LocalDate.parse(e.budgetDate()),CnyAmount.parse(e.amount()))!=1) {
            throw new IllegalStateException("投影汇总未保存");
        }
    }
}
