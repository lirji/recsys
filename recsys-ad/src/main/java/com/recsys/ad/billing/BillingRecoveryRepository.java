package com.recsys.ad.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.ad.persistence.mapper.addb.BillingRecoveryMapper;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.BillingRecovery.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.support.DataAccessUtils;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/** 原事件恢复只更改投递状态；载荷、账本、投影与恢复结果全部保留原始事实。 */
public class BillingRecoveryRepository {
    public static final String MIGRATION = "db/ad-billing/V018__billing_recovery.sql";
    private final BillingRecoveryMapper mapper;
    private final DataSource dataSource;
    private final BillingEventCodec impressions;
    private final ClickEventCodec clicks;
    private final ConversionEventCodec conversions;

    /** 三族严格codec复用原消费者协议，不为恢复绕开版本或身份核查。 */
    public BillingRecoveryRepository(SqlSessionTemplate sessions, ObjectMapper json) {
        mapper = sessions.getMapper(BillingRecoveryMapper.class);
        dataSource = sessions.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource();
        impressions = new BillingEventCodec(json); clicks = new ClickEventCodec(json); conversions = new ConversionEventCodec(json);
    }

    /** 数据源对象身份用于证明恢复与资金效果处于同一本地事务。 */
    public DataSource dataSource() { return dataSource; }

    /** 应用只校验显式迁移，不能为缺表自动创建第二份恢复历史。 */
    public void verifySchema() {
        if (!BudgetRepository.migrationSha256(MIGRATION).equals(DataAccessUtils.requiredSingleResult(mapper.migrationHashes()))) {
            throw new IllegalStateException("恢复命令迁移缺失或漂移");
        }
        mapper.verifySchema();
    }

    boolean insert(Command command) { return mapper.insert(command) == 1; }

    Optional<Result> find(long advertiserId, UUID commandId) {
        return mapper.find(advertiserId, commandId).stream().findFirst().map(row -> new Result(
                new Command(row.commandId(), row.advertiserId(), row.budgetDate(), row.family(), Action.valueOf(row.action()),
                        row.targetId(), row.reason(), row.actor()), Status.valueOf(row.status()), row.createdAt().toInstant(), row.completedAt().toInstant()));
    }

    void complete(Command command, Status status) {
        if (mapper.complete(command, status) != 1) throw new IllegalStateException("恢复结果写入未生效");
    }

    /** 先锁原Outbox再核查；并发正常投影可能先完成，重复重发仍由原Inbox安全吸收。 */
    Status republish(Command command) {
        mapper.lockOutbox(command.family(), command);
        var target = mapper.target(command.family(), command).stream().findFirst().orElse(null);
        if (target == null) return Status.NOT_FOUND;
        if (!target.obligationMatches() || target.deliveryState() == null) return Status.NEEDS_REVIEW;
        try {
            Identity identity = identity(command, target);
            if (!identity.eventId().equals(target.eventId().toString())
                    || !identity.qualificationId().equals(target.qualificationId().toString())
                    || !identity.advertiserId().equals(Long.toString(command.advertiserId()))
                    || !identity.budgetDate().equals(command.budgetDate().toString())
                    || new BigDecimal(identity.amount()).compareTo(target.amount()) != 0
                    || !identity.destination().equals(target.destination())) return Status.NEEDS_REVIEW;
            if (target.factPresent()) {
                return target.factMatches() && identity.hash().equals(target.factHash()) ? Status.ALREADY_APPLIED : Status.NEEDS_REVIEW;
            }
        } catch (BillingEventCodec.InvalidEvent ex) {
            return Status.NEEDS_REVIEW;
        }
        return mapper.requeue(command.family(), command.targetId(), BillingRecovery.REPUBLISH_WAIT_SECONDS) == 1
                ? Status.REQUEUED : Status.NOT_ELIGIBLE;
    }

    private Identity identity(Command command, BillingRecoveryMapper.TargetRow target) {
        return switch (command.family()) {
            case IMPRESSION -> {
                var decoded = impressions.decode(target.partitionKey(), target.payloadJson()); var e = decoded.event();
                yield new Identity(e.eventId(), e.qualificationId(), e.advertiserId(), e.budgetDate(), e.amount(),
                        ImpressionReceipt.CapturedEvent.TOPIC, decoded.hash());
            }
            case CLICK -> {
                var decoded = clicks.decode(target.partitionKey(), target.payloadJson()); var e = decoded.event();
                yield new Identity(e.eventId(), e.qualificationId(), e.advertiserId(), e.budgetDate(), e.amount(),
                        ClickSettlement.CapturedEvent.TOPIC, decoded.hash());
            }
            case CONVERSION -> {
                var decoded = conversions.decode(target.partitionKey(), target.payloadJson()); var e = decoded.event();
                yield new Identity(e.eventId(), e.qualificationId(), e.advertiserId(), e.budgetDate(), e.amount(),
                        ConversionSettlement.CapturedEvent.TOPIC, decoded.hash());
            }
        };
    }

    /** 比对完整codec校验后的关键账本身份和规范载荷摘要，避免恢复坏事件。 */
    private record Identity(String eventId, String qualificationId, String advertiserId, String budgetDate,
                            String amount, String destination, String hash) { }
}
