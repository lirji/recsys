package com.recsys.ad.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.ImpressionReceipt.*;
import com.recsys.common.ad.delivery.SandboxDelivery.Qualification;
import com.recsys.ad.persistence.mapper.addb.ImpressionReceiptMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.support.DataAccessUtils;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 回执、不可变账本和 Outbox 的同库持久化入口；写方法必须由 owner 用例事务调用。 */
public class ImpressionReceiptRepository {
    public static final String MIGRATION = "db/ad-billing/V005__impression_capture.sql";
    /** PENDING仅是事务内部占位，不扩展公开回执终态枚举。 */
    private static final String UNFINISHED_RECEIPT_STATUS = "PENDING";
    private final ImpressionReceiptMapper mapper;
    private final DataSource dataSource;
    private final ObjectMapper json;
    /** Mapper和事务核验均使用实际会话工厂的数据源，事件日期编码保持原契约。 */
    public ImpressionReceiptRepository(SqlSessionTemplate sessions, ObjectMapper json) {
        this.mapper = sessions.getMapper(ImpressionReceiptMapper.class);
        this.dataSource = sessions.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource();
        this.json = json.copy().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    /** 同一 URL 的另一连接池仍不是同一事务资源。 */
    public javax.sql.DataSource dataSource() { return dataSource; }

    /** 首次启用接受前核对 V005，缺少任一事实表不能收单。 */
    public void verifySchema() {
        if (!BudgetRepository.migrationSha256(MIGRATION).equals(DataAccessUtils.requiredSingleResult(mapper.migrationHashes()))) {
            throw new IllegalStateException("adDb V005 捕获迁移缺失或摘要漂移");
        }
        mapper.verifyReceipt();
        mapper.verifyLedger();
        mapper.verifyOutbox();
    }

    /** 同一命令并发通过唯一键等待首个事务，不能在结果未知时换 ID。 */
    public boolean claim(Command command, UUID qualificationId) {
        return mapper.claim(new ImpressionReceiptMapper.Claim(command.receiptId(), qualificationId, command.subject(), command.payloadHash(),
                command.visibleDurationMs(), command.minVisibleRatioBps(), command.pageVisible())) == 1;
    }

    /** 按命令查询时还核对负载摘要；先返回历史结果允许 key 轮换后恢复旧 ACK。 */
    public Optional<Result> original(Command command) {
        return findStored(command.receiptId(), command.subject()).map(stored -> {
            if (!stored.hash().equals(command.payloadHash())) throw new ImpressionReceipt.Conflict();
            return stored.result();
        });
    }
    /** 对外查询只返回原结果，token 和负载摘要不暴露。 */
    public Optional<Result> find(UUID receiptId, String subject) {
        Reservation.validateSubject(subject);
        return findStored(receiptId, subject).map(Stored::result);
    }
    private Optional<Stored> findStored(UUID receiptId, String subject) {
        return mapper.find(receiptId).stream().findFirst().map(row -> {
            if (!subject.equals(row.subject())) throw new Reservation.AccessDenied();
            if (UNFINISHED_RECEIPT_STATUS.equals(row.status())) throw new IllegalStateException("出现未完成的已提交回执");
            var result = new Result(receiptId, row.qualificationId(), Status.valueOf(row.status()), row.ledgerId(), row.canonicalReceiptId(),
                    row.chargedAmount().toPlainString(), row.budgetDate(), row.acceptedAt() == null ? null : row.acceptedAt().toInstant(),
                    row.decidedAt().toInstant());
            return new Stored(row.payloadHash(), result);
        });
    }

    /** 唯一 ledger 决定资格是否已经收费，后到事件不能因账户版本更高而漏记原事实。 */
    public Optional<Result> alreadyCaptured(UUID qualificationId, UUID currentReceiptId, Instant now) {
        return mapper.alreadyCaptured(qualificationId).stream().findFirst().map(row ->
                new Result(currentReceiptId, qualificationId, Status.ALREADY_ACCEPTED, row.ledgerId(), row.receiptId(),
                        row.amount().toPlainString(), row.budgetDate(), row.acceptedAt().toInstant(), now));
    }

    /** 捕获唯一事实插入失败必须回滚 held→charged 与预占终态。 */
    public void appendLedger(UUID ledgerId, Command command, Qualification qualification, Reservation reservation,
                             long accountVersion, Instant acceptedAt) {
        var held = reservation.prepared();
        int rows = mapper.appendLedger(new ImpressionReceiptMapper.Ledger(ledgerId, qualification.childId(), command.receiptId(),
                qualification.operationId(), held.advertiserId(), held.adId(), qualification.creativeId(), qualification.userId(), qualification.slot(),
                held.budgetDate(), held.amount(), accountVersion, Timestamp.from(acceptedAt)));
        if (rows != 1) throw new IllegalStateException("账本事实未写入");
    }

    /** 与资金和 ledger 同事务写待发事件，不在此处执行任何 broker 调用。 */
    public void appendOutbox(CapturedEvent event) {
        String payload;
        try { payload = json.writeValueAsString(event); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("账本事件无法编码"); }
        int rows = mapper.appendOutbox(new ImpressionReceiptMapper.Outbox(UUID.fromString(event.eventId()), event.eventType(), event.schemaVersion(),
                CapturedEvent.TOPIC, event.advertiserId() + ":" + event.budgetDate(), payload));
        if (rows != 1) throw new IllegalStateException("账本事件未写入");
    }

    /** 业务拒绝与接受都保存首次结果；失败使此前资金/事实更新整体回滚。 */
    public Result complete(Result result) {
        int rows = mapper.complete(new ImpressionReceiptMapper.Completed(result.receiptId(), result.status().name(), result.ledgerId(),
                result.canonicalReceiptId(), CnyAmount.parse(result.chargedAmount()), result.budgetDate(),
                result.acceptedAt() == null ? null : Timestamp.from(result.acceptedAt()), Timestamp.from(result.decidedAt())));
        if (rows != 1) throw new IllegalStateException("回执结果保存未生效");
        return result;
    }
    private record Stored(String hash, Result result) {}
}
