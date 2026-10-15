package com.recsys.ad.billing;

import com.recsys.common.ad.billing.BudgetAccount;
import com.recsys.common.ad.billing.BudgetCommand;
import com.recsys.common.ad.billing.BudgetCommandResult;
import org.springframework.core.io.ClassPathResource;
import com.recsys.ad.persistence.mapper.addb.BudgetMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.support.DataAccessUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/** 广告账务 owner 的唯一 SQL 入口；所有写操作由 BudgetAccountService 的 adDb 本地事务包围。 */
public class BudgetRepository {
    public static final String MIGRATION = "db/ad-billing/V001__budget_account.sql";
    /** 数据库事务内中间态，不属于公开BudgetCommandResult.Status的最终结果集合。 */
    private static final String UNFINISHED_COMMAND_STATUS = "PENDING";
    private final BudgetMapper mapper;
    private final DataSource dataSource;

    /** 连接资源身份从真实会话工厂取得，不能由另一独立DataSource参数伪装同源。 */
    public BudgetRepository(SqlSessionTemplate sessions) {
        this.mapper = sessions.getMapper(BudgetMapper.class);
        this.dataSource = sessions.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource();
    }

    /** 供装配校验连接池身份；同 URL 的另一个连接池也不是同一事务资源。 */
    public DataSource dataSource() {
        return dataSource;
    }

    /** 能力开启时只校验，不在应用启动时擅自修改数据库。 */
    public void verifySchema() {
        String installed = DataAccessUtils.requiredSingleResult(mapper.migrationHashes());
        if (!migrationSha256().equals(installed)) {
            throw new IllegalStateException("adDb 预算迁移缺失或脚本摘要不一致");
        }
        mapper.verifyAccount();
        mapper.verifyCommand();
    }

    /** 执行记录与随包脚本比对，防止已执行迁移被编辑后继续启用账务。 */
    public static String migrationSha256() {
        return migrationSha256(MIGRATION);
    }

    /** 后续迁移逐版本核对，不能通过覆盖旧摘要伪造升级。 */
    static String migrationSha256(String resource) {
        try (var stream = new ClassPathResource(resource).getInputStream()) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("无法校验账务迁移脚本", ex);
        }
    }

    /** 唯一键会等待并发事务提交；重复命令随后读取已提交结果，不做检查后插入。 */
    public Optional<BudgetCommandResult> claim(BudgetCommand command) {
        int inserted = mapper.claim(command, command.payloadHash());
        if (inserted == 1) return Optional.empty();
        String originalHash = DataAccessUtils.requiredSingleResult(mapper.payloadHashes(command.advertiserId(), command.commandId()));
        if (!command.payloadHash().equals(originalHash)) throw new BudgetCommandResult.PayloadConflict();
        return Optional.of(findCommand(command.advertiserId(), command.commandId()).orElseThrow());
    }

    /** 先建立零版本账户，再持有行锁，首次创建与并发调额共用同一串行化点。 */
    public BudgetAccount lockAccount(long advertiserId, LocalDate budgetDate) {
        mapper.insertAccount(advertiserId, budgetDate);
        return DataAccessUtils.requiredSingleResult(mapper.lockAccount(advertiserId, budgetDate));
    }

    /** 行锁之外仍带版本条件并检查影响行数，禁止并发冲突伪装成功。 */
    public BudgetAccount apply(BudgetCommand command) {
        var results = mapper.apply(command);
        if (results.size() != 1) throw new IllegalStateException("预算条件更新未生效");
        return results.getFirst();
    }

    /** 与账户修改同事务保存原始快照；失败必须向外抛出以触发整体回滚。 */
    public void complete(BudgetCommandResult result) {
        int changed = mapper.complete(result, result.status().name());
        if (changed != 1) throw new IllegalStateException("预算命令结果未保存");
    }

    /** 当前权威账户，空结果不推断任何在途命令的执行结论。 */
    public Optional<BudgetAccount> findAccount(long advertiserId, LocalDate date) {
        return mapper.accounts(advertiserId, date).stream().findFirst();
    }

    /** 查询首次结果，绝不重新关联最新账户覆盖历史结果。 */
    public Optional<BudgetCommandResult> findCommand(long advertiserId, UUID commandId) {
        return mapper.commands(advertiserId, commandId).stream().findFirst().map(row -> {
            if (UNFINISHED_COMMAND_STATUS.equals(row.status())) {
                throw new IllegalStateException("发现未完成预算命令，需核查事务边界");
            }
            var account = new BudgetAccount(row.advertiserId(), row.budgetDate(), row.limitAmount(), row.heldAmount(),
                    row.chargedAmount(), row.version() == null ? 0L : row.version());
            return new BudgetCommandResult(row.commandId(), BudgetCommandResult.Status.valueOf(row.status()), account);
        });
    }
}
