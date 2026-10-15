package com.recsys.ad.billing;

import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.BillingReconciliation.*;
import com.recsys.common.ad.delivery.SandboxDelivery.ChargeMode;
import com.recsys.ad.persistence.mapper.addb.ReconciliationMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.support.DataAccessUtils;
import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

/** 只读对账SQL集中于资金owner；表名只来自服务端封闭枚举，查询值全部绑定。 */
public class ReconciliationRepository {
    public static final String MIGRATION="db/ad-billing/V016__reconciliation_indexes.sql";
    private final ReconciliationMapper mapper;
    private final DataSource dataSource;
    /** 会话与事务管理器必须绑定实际同一资源；XML保留每句5秒超时。 */
    public ReconciliationRepository(SqlSessionTemplate sessions) {
        this.mapper=sessions.getMapper(ReconciliationMapper.class);
        this.dataSource=sessions.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource();
    }
    /** 比较事务资源身份，不把同URL的另一个连接池误认为同一事务。 */
    public javax.sql.DataSource dataSource() {return dataSource;}
    /** 新查询必须能完整读取三族事实；启动不自动补表或把缺失模式解释为0。 */
    public void verifySchema() {
        var migrations=Map.ofEntries(Map.entry(1,BudgetRepository.MIGRATION),Map.entry(3,ReservationRepository.MIGRATION),
                Map.entry(5,ImpressionReceiptRepository.MIGRATION),Map.entry(6,BillingOutboxRepository.MIGRATION),
                Map.entry(8,ClickQuoteRepository.MIGRATION),Map.entry(9,ClickSettlementRepository.MIGRATION),
                Map.entry(10,"db/ad-billing/V010__click_event_delivery.sql"),Map.entry(12,ConversionQuoteRepository.MIGRATION),
                Map.entry(13,ConversionSettlementRepository.MIGRATION),Map.entry(14,"db/ad-billing/V014__conversion_event_delivery.sql"),Map.entry(16,MIGRATION));
        migrations.forEach((version,path)->{
            var installed=DataAccessUtils.requiredSingleResult(mapper.migrationHashes(version));
            if(!BudgetRepository.migrationSha256(path).equals(installed))throw new IllegalStateException("对账所需adDb迁移缺失或漂移");
        });
        // 历史摘要存在也不能代替实际表可读；LIMIT 0只检查结构，不扫描账户数据。
        for(var family:ChargeMode.values()) {
            mapper.verifyLedger(family);mapper.verifyOutbox(family);mapper.verifyFact(family);
            mapper.verifyDaily(family);mapper.verifyObligations(family);
        }
    }
    /** 首次查询建立同事务MVCC快照；后续所有统计共享它，时间仅用于本次到期判读。 */
    public Instant observedAt() {return DataAccessUtils.requiredSingleResult(mapper.observedAt()).toInstant();}
    /** 不锁账户；不存在与查询失败明确区分。 */
    public Optional<BudgetAccount> account(Scope scope) {
        return mapper.account(scope).stream().findFirst().map(row->new BudgetAccount(row.advertiserId(),scope.budgetDate(),
                row.limitAmount(),row.heldAmount(),row.chargedAmount(),row.version()));
    }

    /** 逐事件比对关键身份与金额；零元事件通过笔数/ID发现缺口，不能只看sum。 */
    public FamilySummary family(Scope scope,ChargeMode family,Instant observedAt) {
        java.util.Objects.requireNonNull(family);
        var row=DataAccessUtils.requiredSingleResult(mapper.family(scope,family,observedAt.atOffset(java.time.ZoneOffset.UTC),family!=ChargeMode.IMPRESSION));
        return new FamilySummary(family,row.heldCount(),BillingReconciliation.decimal(row.heldAmount()),row.expiredHeldCount(),
                row.ledgerCount(),BillingReconciliation.decimal(row.ledgerAmount()),row.missingLedgerCount(),
                row.invalidLedgerCount(),row.pendingOutboxCount(),row.deadOutboxCount(),
                row.missingOutboxCount(),instant(row.oldestPendingAt()),row.factCount(),
                BillingReconciliation.decimal(row.factAmount()),row.missingFactCount(),row.invalidFactCount(),
                row.unexpectedFactCount(),instant(row.lastAppliedAt()),row.summaryPresent(),
                row.summaryCount(),BillingReconciliation.decimal(row.summaryAmount()));
    }
    private static Instant instant(OffsetDateTime value) {return value==null?null:value.toInstant();}
}
