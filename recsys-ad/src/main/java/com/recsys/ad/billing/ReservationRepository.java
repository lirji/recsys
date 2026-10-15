package com.recsys.ad.billing;

import com.recsys.common.ad.billing.BudgetAccount;
import com.recsys.common.ad.billing.FrozenCpmQuote;
import com.recsys.common.ad.billing.Reservation;
import com.recsys.ad.persistence.mapper.addb.ReservationMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.support.DataAccessUtils;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** 预占/账户 SQL 归同一 adDb owner；用例在外层控制本地事务与统一锁序。 */
public class ReservationRepository {
    public static final String MIGRATION = "db/ad-billing/V003__budget_reservation.sql";
    /** PENDING只存在于数据库事务中，不扩展公开Reservation.State的已决定状态集合。 */
    private static final String UNFINISHED_RESERVATION_STATE = "PENDING";
    private final ReservationMapper mapper;
    private final DataSource dataSource;

    /** 从真实会话工厂取得owner连接资源，外层事务仍以对象身份核验。 */
    public ReservationRepository(SqlSessionTemplate sessions) {
        this.mapper = sessions.getMapper(ReservationMapper.class);
        this.dataSource = sessions.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource();
    }

    /** 校验本地事务资源身份，不能把同 URL 的另一连接池视为同一事务。 */
    public DataSource dataSource() { return dataSource; }

    /** 显式迁移完成后才允许启用预占与扫描。 */
    public void verifySchema() {
        String installed = DataAccessUtils.requiredSingleResult(mapper.migrationHashes());
        if (!BudgetRepository.migrationSha256(MIGRATION).equals(installed)) {
            throw new IllegalStateException("adDb 预占迁移缺失或摘要不一致");
        }
        mapper.verifyReservation();
    }

    /** 业务日和到期判断使用同一数据库时钟，避免应用实例时钟漂移。 */
    public Instant databaseNow() {
        return mapper.databaseNow().toInstant();
    }

    /** 唯一键领取放在账户锁前；竞争事务结束后才能判定同命令重试还是冲突。 */
    public boolean claim(UUID reservationId, FrozenCpmQuote quote, String subject, LocalDate day, Instant acceptedAt) {
        int inserted = mapper.claim(new ReservationMapper.Claim(reservationId, quote.quoteId(), quote.advertiserId(), quote.adId(),
                subject, day, quote.price().amount(), Timestamp.from(quote.expiresAt()), Timestamp.from(acceptedAt)));
        if (inserted != 0 && inserted != 1) throw new IllegalStateException("预占领取行数非法");
        return inserted == 1;
    }

    /** 账户不自动创建；缺账户必须由管理员建立预算后用新的有意命令准备。 */
    public Optional<BudgetAccount> lockAccount(long advertiserId, LocalDate day) {
        return mapper.lockAccount(advertiserId, day).stream().findFirst();
    }

    /** 硬额度由条件更新与数据库约束兜底，不能依赖前面的读取结论。 */
    public boolean hold(BudgetAccount account, java.math.BigDecimal amount) {
        int changed = mapper.hold(account, amount);
        if (changed != 0 && changed != 1) throw new IllegalStateException("预占账户更新行数非法");
        return changed == 1;
    }

    /** 结果保存失败必须使账户 held 的增加一并回滚。 */
    public Reservation.Prepared complete(UUID reservationId, Reservation.Decision decision) {
        int changed = mapper.complete(reservationId, decision.name(),
                (decision == Reservation.Decision.HELD ? Reservation.State.HELD : Reservation.State.REJECTED).name());
        if (changed != 1) throw new IllegalStateException("预占决策保存未生效 reservationId=" + reservationId);
        return find(reservationId, false).orElseThrow().prepared();
    }

    /** 查询当前状态；只有修改用例取行锁。SQL 选择来自服务端固定分支。 */
    public Optional<Reservation> find(UUID reservationId, boolean lock) {
        return mapper.find(reservationId, lock).stream().findFirst().map(ReservationRepository::reservation);
    }

    /** 每笔只领取一行；语句开始时间可作为索引边界，实际释放前再检查数据库当前时间。 */
    public Optional<Reservation> lockOneExpired() {
        return mapper.lockOneExpired().stream().findFirst().map(ReservationRepository::reservation);
    }

    /** 捕获只从 HELD 转移，和释放使用同一锁序；任何更新行数异常使整个回执事务回滚。 */
    public BudgetAccount capture(Reservation reservation, BudgetAccount account, Instant acceptedAt) {
        var held = reservation.prepared();
        if (reservation.state() != Reservation.State.HELD || account.advertiserId() != held.advertiserId()
                || !account.budgetDate().equals(held.budgetDate()) || !acceptedAt.isBefore(held.expiresAt())) {
            throw new IllegalArgumentException("预占不可捕获或账户/期限不一致");
        }
        int changed = mapper.captureAccount(account, held.amount());
        if (changed != 1) throw new IllegalStateException("捕获账户未生效 reservationId=" + held.reservationId());
        changed = mapper.captureReservation(held.reservationId(), reservation.version(), Timestamp.from(acceptedAt));
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
        int changed = mapper.releaseAccount(account, prepared.amount());
        if (changed != 1) throw new IllegalStateException("释放账户未生效，需核查预占总额 reservationId=" + prepared.reservationId());
        changed = mapper.close(prepared.reservationId(), reservation.version(), state.name(), reason, Timestamp.from(now));
        if (changed != 1) throw new IllegalStateException("释放预占状态未生效 reservationId=" + prepared.reservationId());
        return find(prepared.reservationId(), false).orElseThrow();
    }
    private static Reservation reservation(ReservationMapper.Stored row) {
        if (UNFINISHED_RESERVATION_STATE.equals(row.state())) throw new IllegalStateException("发现未提交完成的预占决策");
        var prepared = new Reservation.Prepared(row.reservationId(), row.quoteId(), row.advertiserId(), row.adId(), row.subjectKey(),
                row.budgetDate(), row.amount(), row.expiresAt().toInstant(), Reservation.Decision.valueOf(row.decision()), row.createdAt().toInstant());
        return new Reservation(prepared, Reservation.State.valueOf(row.state()), row.version(),
                row.closedAt() == null ? null : row.closedAt().toInstant(), row.closeReason());
    }

}
