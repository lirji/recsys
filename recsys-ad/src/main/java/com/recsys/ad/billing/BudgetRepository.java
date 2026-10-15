package com.recsys.ad.billing;

import com.recsys.common.ad.billing.BudgetAccount;
import com.recsys.common.ad.billing.BudgetCommand;
import com.recsys.common.ad.billing.BudgetCommandResult;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/** 广告账务 owner 的唯一 SQL 入口；所有写操作由 BudgetAccountService 的 adDb 本地事务包围。 */
public class BudgetRepository {
    public static final String MIGRATION = "db/ad-billing/V001__budget_account.sql";
    private final JdbcTemplate jdbc;
    private static final RowMapper<BudgetAccount> ACCOUNT = (rs, row) -> account(rs, "");

    public BudgetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 供装配校验连接池身份；同 URL 的另一个连接池也不是同一事务资源。 */
    public DataSource dataSource() {
        return jdbc.getDataSource();
    }

    /** 能力开启时只校验，不在应用启动时擅自修改数据库。 */
    public void verifySchema() {
        String installed = jdbc.queryForObject(
                "SELECT script_sha256 FROM ad_billing_schema_history WHERE version = 1", String.class);
        if (!migrationSha256().equals(installed)) {
            throw new IllegalStateException("adDb 预算迁移缺失或脚本摘要不一致");
        }
        jdbc.queryForList("SELECT limit_amount, held_amount, charged_amount, version FROM ad_budget_account LIMIT 0");
        jdbc.queryForList("SELECT payload_hash, status, result_version FROM ad_budget_command LIMIT 0");
    }

    /** 执行记录与随包脚本比对，防止已执行迁移被编辑后继续启用账务。 */
    public static String migrationSha256() {
        try (var stream = new ClassPathResource(MIGRATION).getInputStream()) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("无法校验账务迁移脚本", ex);
        }
    }

    /** 唯一键会等待并发事务提交；重复命令随后读取已提交结果，不做检查后插入。 */
    public Optional<BudgetCommandResult> claim(BudgetCommand command) {
        int inserted = jdbc.update("""
                INSERT INTO ad_budget_command (traffic_class, advertiser_id, command_id, budget_date, currency,
                    requested_limit, expected_version, actor, payload_hash, status)
                VALUES ('SANDBOX', ?, ?, ?, 'CNY', ?, ?, ?, ?, 'PENDING')
                ON CONFLICT (traffic_class, advertiser_id, command_id) DO NOTHING
                """, command.advertiserId(), command.commandId(), command.budgetDate(), command.limitAmount(),
                command.expectedVersion(), command.actor(), command.payloadHash());
        if (inserted == 1) return Optional.empty();
        String originalHash = jdbc.queryForObject("""
                SELECT payload_hash FROM ad_budget_command
                WHERE traffic_class = 'SANDBOX' AND advertiser_id = ? AND command_id = ?
                """, String.class, command.advertiserId(), command.commandId());
        if (!command.payloadHash().equals(originalHash)) throw new BudgetCommandResult.PayloadConflict();
        return Optional.of(findCommand(command.advertiserId(), command.commandId()).orElseThrow());
    }

    /** 先建立零版本账户，再持有行锁，首次创建与并发调额共用同一串行化点。 */
    public BudgetAccount lockAccount(long advertiserId, LocalDate budgetDate) {
        jdbc.update("""
                INSERT INTO ad_budget_account
                    (traffic_class, advertiser_id, budget_date, currency, limit_amount, held_amount, charged_amount, version)
                VALUES ('SANDBOX', ?, ?, 'CNY', 0, 0, 0, 0) ON CONFLICT DO NOTHING
                """, advertiserId, budgetDate);
        return jdbc.queryForObject("""
                SELECT * FROM ad_budget_account
                WHERE traffic_class = 'SANDBOX' AND advertiser_id = ? AND budget_date = ? AND currency = 'CNY'
                FOR UPDATE
                """, ACCOUNT, advertiserId, budgetDate);
    }

    /** 行锁之外仍带版本条件并检查影响行数，禁止并发冲突伪装成功。 */
    public BudgetAccount apply(BudgetCommand command) {
        var results = jdbc.query("""
                UPDATE ad_budget_account SET limit_amount = ?, version = version + 1, updated_at = clock_timestamp()
                WHERE traffic_class = 'SANDBOX' AND advertiser_id = ? AND budget_date = ? AND currency = 'CNY'
                    AND version = ? AND held_amount + charged_amount <= ?
                RETURNING *
                """, ACCOUNT, command.limitAmount(), command.advertiserId(), command.budgetDate(),
                command.expectedVersion(), command.limitAmount());
        if (results.size() != 1) throw new IllegalStateException("预算条件更新未生效");
        return results.getFirst();
    }

    /** 与账户修改同事务保存原始快照；失败必须向外抛出以触发整体回滚。 */
    public void complete(BudgetCommandResult result) {
        BudgetAccount account = result.account();
        int changed = jdbc.update("""
                UPDATE ad_budget_command SET status = ?, result_limit = ?, result_held = ?, result_charged = ?,
                    result_version = ?, completed_at = clock_timestamp()
                WHERE traffic_class = 'SANDBOX' AND advertiser_id = ? AND command_id = ? AND status = 'PENDING'
                """, result.status().name(), account.limitAmount(), account.heldAmount(), account.chargedAmount(),
                account.version(), account.advertiserId(), result.commandId());
        if (changed != 1) throw new IllegalStateException("预算命令结果未保存");
    }

    /** 当前权威账户，空结果不推断任何在途命令的执行结论。 */
    public Optional<BudgetAccount> findAccount(long advertiserId, LocalDate date) {
        return jdbc.query("""
                SELECT * FROM ad_budget_account
                WHERE traffic_class = 'SANDBOX' AND advertiser_id = ? AND budget_date = ? AND currency = 'CNY'
                """, ACCOUNT, advertiserId, date).stream().findFirst();
    }

    /** 查询首次结果，绝不重新关联最新账户覆盖历史结果。 */
    public Optional<BudgetCommandResult> findCommand(long advertiserId, UUID commandId) {
        return jdbc.query("""
                SELECT * FROM ad_budget_command
                WHERE traffic_class = 'SANDBOX' AND advertiser_id = ? AND command_id = ?
                """, (rs, row) -> {
            if ("PENDING".equals(rs.getString("status"))) {
                throw new IllegalStateException("发现未完成预算命令，需核查事务边界");
            }
            return new BudgetCommandResult(rs.getObject("command_id", UUID.class),
                    BudgetCommandResult.Status.valueOf(rs.getString("status")), account(rs, "result_"));
        }, advertiserId, commandId).stream().findFirst();
    }

    private static BudgetAccount account(ResultSet rs, String prefix) throws SQLException {
        return new BudgetAccount(rs.getLong("advertiser_id"), rs.getObject("budget_date", LocalDate.class),
                rs.getBigDecimal(prefix.isEmpty() ? "limit_amount" : "result_limit"),
                rs.getBigDecimal(prefix.isEmpty() ? "held_amount" : "result_held"),
                rs.getBigDecimal(prefix.isEmpty() ? "charged_amount" : "result_charged"),
                rs.getLong(prefix + "version"));
    }
}
