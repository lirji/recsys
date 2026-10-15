package com.recsys.ad.billing;

import com.recsys.common.ad.billing.BudgetAccount;
import com.recsys.common.ad.billing.FrozenCpmQuote;
import com.recsys.common.ad.billing.Reservation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** 预占/账户 SQL 归同一 adDb owner；用例在外层控制本地事务与统一锁序。 */
public class ReservationRepository {
    public static final String MIGRATION = "db/ad-billing/V003__budget_reservation.sql";
    private final JdbcTemplate jdbc;
    private static final RowMapper<Reservation> RESERVATION = (rs, row) -> {
        if ("PENDING".equals(rs.getString("state"))) throw new IllegalStateException("发现未提交完成的预占决策");
        var prepared = new Reservation.Prepared(rs.getObject("reservation_id", UUID.class), rs.getObject("quote_id", UUID.class),
                rs.getLong("advertiser_id"), rs.getLong("ad_id"), rs.getString("subject_key"),
                rs.getObject("budget_date", LocalDate.class), rs.getBigDecimal("amount"),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                Reservation.Decision.valueOf(rs.getString("decision")), rs.getObject("created_at", OffsetDateTime.class).toInstant());
        OffsetDateTime closed = rs.getObject("closed_at", OffsetDateTime.class);
        return new Reservation(prepared, Reservation.State.valueOf(rs.getString("state")), rs.getLong("version"),
                closed == null ? null : closed.toInstant(), rs.getString("close_reason"));
    };

    public ReservationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 校验本地事务资源身份，不能把同 URL 的另一连接池视为同一事务。 */
    public DataSource dataSource() { return jdbc.getDataSource(); }

    /** 显式迁移完成后才允许启用预占与扫描。 */
    public void verifySchema() {
        String installed = jdbc.queryForObject("SELECT script_sha256 FROM ad_billing_schema_history WHERE version = 3", String.class);
        if (!BudgetRepository.migrationSha256(MIGRATION).equals(installed)) {
            throw new IllegalStateException("adDb 预占迁移缺失或摘要不一致");
        }
        jdbc.queryForList("SELECT reservation_id, decision, state, expires_at FROM ad_budget_reservation LIMIT 0");
    }

    /** 业务日和到期判断使用同一数据库时钟，避免应用实例时钟漂移。 */
    public Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
    }

    /** 唯一键领取放在账户锁前；竞争事务结束后才能判定同命令重试还是冲突。 */
    public boolean claim(UUID reservationId, FrozenCpmQuote quote, String subject, LocalDate day, Instant acceptedAt) {
        int inserted = jdbc.update("""
                INSERT INTO ad_budget_reservation (traffic_class, reservation_id, quote_id, advertiser_id, ad_id,
                    subject_key, budget_date, amount, expires_at, state, created_at, updated_at)
                VALUES ('SANDBOX', ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?) ON CONFLICT DO NOTHING
                """, reservationId, quote.quoteId(), quote.advertiserId(), quote.adId(), subject, day,
                quote.price().amount(), Timestamp.from(quote.expiresAt()), Timestamp.from(acceptedAt), Timestamp.from(acceptedAt));
        if (inserted != 0 && inserted != 1) throw new IllegalStateException("预占领取行数非法");
        return inserted == 1;
    }

    /** 账户不自动创建；缺账户必须由管理员建立预算后用新的有意命令准备。 */
    public Optional<BudgetAccount> lockAccount(long advertiserId, LocalDate day) {
        return jdbc.query("""
                SELECT * FROM ad_budget_account WHERE traffic_class = 'SANDBOX' AND advertiser_id = ?
                    AND budget_date = ? AND currency = 'CNY' FOR UPDATE
                """, (rs, row) -> new BudgetAccount(advertiserId, day, rs.getBigDecimal("limit_amount"),
                rs.getBigDecimal("held_amount"), rs.getBigDecimal("charged_amount"), rs.getLong("version")), advertiserId, day)
                .stream().findFirst();
    }

    /** 硬额度由条件更新与数据库约束兜底，不能依赖前面的读取结论。 */
    public boolean hold(BudgetAccount account, java.math.BigDecimal amount) {
        int changed = jdbc.update("""
                UPDATE ad_budget_account SET held_amount = held_amount + ?, version = version + 1, updated_at = clock_timestamp()
                WHERE traffic_class = 'SANDBOX' AND advertiser_id = ? AND budget_date = ? AND currency = 'CNY'
                    AND version = ? AND limit_amount - held_amount - charged_amount >= ?
                """, amount, account.advertiserId(), account.budgetDate(), account.version(), amount);
        if (changed != 0 && changed != 1) throw new IllegalStateException("预占账户更新行数非法");
        return changed == 1;
    }

    /** 结果保存失败必须使账户 held 的增加一并回滚。 */
    public Reservation.Prepared complete(UUID reservationId, Reservation.Decision decision) {
        int changed = jdbc.update("""
                UPDATE ad_budget_reservation SET decision = ?, state = ?, updated_at = clock_timestamp()
                WHERE reservation_id = ? AND traffic_class = 'SANDBOX' AND state = 'PENDING'
                """, decision.name(), decision == Reservation.Decision.HELD ? "HELD" : "REJECTED", reservationId);
        if (changed != 1) throw new IllegalStateException("预占决策保存未生效 reservationId=" + reservationId);
        return find(reservationId, false).orElseThrow().prepared();
    }

    /** 查询当前状态；只有修改用例取行锁。SQL 选择来自服务端固定分支。 */
    public Optional<Reservation> find(UUID reservationId, boolean lock) {
        String sql = lock
                ? "SELECT * FROM ad_budget_reservation WHERE traffic_class = 'SANDBOX' AND reservation_id = ? FOR UPDATE"
                : "SELECT * FROM ad_budget_reservation WHERE traffic_class = 'SANDBOX' AND reservation_id = ?";
        return jdbc.query(sql, RESERVATION, reservationId).stream().findFirst();
    }

    /** 每笔只领取一行；语句开始时间可作为索引边界，实际释放前再检查数据库当前时间。 */
    public Optional<Reservation> lockOneExpired() {
        return jdbc.query("""
                SELECT * FROM ad_budget_reservation WHERE traffic_class = 'SANDBOX' AND state = 'HELD'
                    AND expires_at <= statement_timestamp()
                ORDER BY expires_at, reservation_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, RESERVATION).stream().findFirst();
    }

    /** 捕获只从 HELD 转移，和释放使用同一锁序；任何更新行数异常使整个回执事务回滚。 */
    public BudgetAccount capture(Reservation reservation, BudgetAccount account, Instant acceptedAt) {
        var held = reservation.prepared();
        if (reservation.state() != Reservation.State.HELD || account.advertiserId() != held.advertiserId()
                || !account.budgetDate().equals(held.budgetDate()) || !acceptedAt.isBefore(held.expiresAt())) {
            throw new IllegalArgumentException("预占不可捕获或账户/期限不一致");
        }
        int changed = jdbc.update("""
                UPDATE ad_budget_account SET held_amount=held_amount-?, charged_amount=charged_amount+?,
                    version=version+1, updated_at=clock_timestamp()
                WHERE traffic_class='SANDBOX' AND advertiser_id=? AND budget_date=? AND currency='CNY'
                    AND version=? AND held_amount>=?
                """, held.amount(), held.amount(), account.advertiserId(), account.budgetDate(), account.version(), held.amount());
        if (changed != 1) throw new IllegalStateException("捕获账户未生效 reservationId=" + held.reservationId());
        changed = jdbc.update("""
                UPDATE ad_budget_reservation SET state='CAPTURED', close_reason='IMPRESSION_CAPTURED', closed_at=?,
                    updated_at=clock_timestamp(), version=version+1
                WHERE traffic_class='SANDBOX' AND reservation_id=? AND state='HELD' AND version=?
                """, Timestamp.from(acceptedAt), held.reservationId(), reservation.version());
        if (changed != 1) throw new IllegalStateException("捕获预占状态未生效 reservationId=" + held.reservationId());
        return new BudgetAccount(account.advertiserId(), account.budgetDate(), account.limitAmount(),
                account.heldAmount().subtract(held.amount()), account.chargedAmount().add(held.amount()), account.version()+1);
    }

    /** 释放账户与预占终态一起提交；任一条件更新失败都抛异常回滚。 */
    public Reservation close(Reservation reservation, BudgetAccount account, Instant now) {
        var prepared = reservation.prepared();
        boolean expired = !now.isBefore(prepared.expiresAt());
        var state = expired ? Reservation.State.EXPIRED : Reservation.State.RELEASED;
        String reason = expired ? "EXPIRED" : "DELIVERY_ABORTED";
        int changed = jdbc.update("""
                UPDATE ad_budget_account SET held_amount = held_amount - ?, version = version + 1, updated_at = clock_timestamp()
                WHERE traffic_class = 'SANDBOX' AND advertiser_id = ? AND budget_date = ? AND currency = 'CNY'
                    AND version = ? AND held_amount >= ?
                """, prepared.amount(), account.advertiserId(), account.budgetDate(), account.version(), prepared.amount());
        if (changed != 1) throw new IllegalStateException("释放账户未生效，需核查预占总额 reservationId=" + prepared.reservationId());
        changed = jdbc.update("""
                UPDATE ad_budget_reservation SET state = ?, close_reason = ?, closed_at = ?, updated_at = ?, version = version + 1
                WHERE traffic_class = 'SANDBOX' AND reservation_id = ? AND state = 'HELD' AND version = ?
                """, state.name(), reason, Timestamp.from(now), Timestamp.from(now), prepared.reservationId(), reservation.version());
        if (changed != 1) throw new IllegalStateException("释放预占状态未生效 reservationId=" + prepared.reservationId());
        return find(prepared.reservationId(), false).orElseThrow();
    }
}
