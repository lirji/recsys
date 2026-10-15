package com.recsys.ad.persistence.mapper.addb;

import com.recsys.common.ad.billing.BudgetAccount;
import com.recsys.common.ad.billing.BudgetCommand;
import com.recsys.common.ad.billing.BudgetCommandResult;
import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 预算账户与幂等命令SQL；所有写入和锁读取必须服从owner的同源事务。 */
public interface BudgetMapper {
    /** 必须存在且仅一条迁移记录，由Repository校验摘要。 */
    List<String> migrationHashes();
    /** 固定schema探针，不创建或修改表。 */
    void verifyAccount();
    /** 校验命令结果必要列，避免带缺失迁移启动。 */
    void verifyCommand();
    /** 唯一键竞争由数据库串行化，不能先查询再插入。 */
    int claim(@Param("command") BudgetCommand command, @Param("payloadHash") String payloadHash);
    /** 同一幂等键的原始摘要，不覆盖首次请求。 */
    List<String> payloadHashes(@Param("advertiserId") long advertiserId, @Param("commandId") UUID commandId);
    /** 幂等建立零版本账户，随后仍必须持有账户行锁。 */
    int insertAccount(@Param("advertiserId") long advertiserId, @Param("budgetDate") LocalDate budgetDate);
    /** 不得缓存此结果，行锁属于当前用例事务。 */
    List<BudgetAccount> lockAccount(@Param("advertiserId") long advertiserId, @Param("budgetDate") LocalDate budgetDate);
    /** UPDATE RETURNING显式标记写入；版本/已承诺金额条件不能去掉。 */
    List<BudgetAccount> apply(BudgetCommand command);
    /** 只从PENDING保存首次结果，Repository检查实际影响行数。 */
    int complete(@Param("result") BudgetCommandResult result, @Param("status") String status);
    /** 当前权威余额，不代表某命令是否曾执行。 */
    List<BudgetAccount> accounts(@Param("advertiserId") long advertiserId, @Param("budgetDate") LocalDate budgetDate);
    /** 原结果快照独立读取，禁止重新关联当前账户。 */
    List<CommandRow> commands(@Param("advertiserId") long advertiserId, @Param("commandId") UUID commandId);

    /** PENDING允许结果为空，先由Repository拒绝半成品，再构造经过校验的账户快照。 */
    record CommandRow(UUID commandId, String status, long advertiserId, LocalDate budgetDate,
                      BigDecimal limitAmount, BigDecimal heldAmount, BigDecimal chargedAmount, Long version) { }
}
