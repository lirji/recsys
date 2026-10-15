package com.recsys.proto;

import com.google.protobuf.Timestamp;
import com.recsys.common.ad.billing.BudgetCommand;
import com.recsys.common.ad.billing.ImpressionReceipt;
import com.recsys.proto.impression.v1.*;
import java.time.Instant;

/** 观测整数和精确金额跨进程无损，未知结果状态拒绝解析。 */
public final class ImpressionProtoMapper {
    private ImpressionProtoMapper() {}
    /** 主体由 engine 认证边界注入，不能用终端模拟用户替代。 */
    public static VisibleReceiptCommand toProto(ImpressionReceipt.Command command) {
        return VisibleReceiptCommand.newBuilder().setReceiptId(command.receiptId().toString()).setSubject(command.subject())
                .setQualificationToken(command.qualificationToken()).setVisibleDurationMs(command.visibleDurationMs())
                .setMinVisibleRatioBps(command.minVisibleRatioBps()).setPageVisible(command.pageVisible()).build();
    }
    /** owner 再执行相同领域校验，不能只信任 HTTP 已验证。 */
    public static ImpressionReceipt.Command fromProto(VisibleReceiptCommand command) {
        return new ImpressionReceipt.Command(BudgetCommand.parseId(command.getReceiptId()), command.getSubject(), command.getQualificationToken(),
                command.getVisibleDurationMs(), command.getMinVisibleRatioBps(), command.getPageVisible());
    }
    /** 返回已提交原账本标识和金额，拒绝没有伪造的零 UUID。 */
    public static VisibleReceiptResult toProto(ImpressionReceipt.Result result) {
        var b = VisibleReceiptResult.newBuilder().setReceiptId(result.receiptId().toString()).setQualificationId(result.qualificationId().toString())
                .setStatus(result.status().name()).setChargedAmount(result.chargedAmount()).setDecidedAt(time(result.decidedAt()));
        if (result.ledgerId()!=null) b.setLedgerId(result.ledgerId().toString());
        if (result.canonicalReceiptId()!=null) b.setCanonicalReceiptId(result.canonicalReceiptId().toString());
        if (result.budgetDate()!=null) b.setBudgetDate(result.budgetDate().toString());
        if (result.acceptedAt()!=null) b.setAcceptedAt(time(result.acceptedAt()));
        return b.build();
    }
    /** 原预算日和微秒时间不能被接收端的本地时区重算。 */
    public static ImpressionReceipt.Result fromProto(VisibleReceiptResult result) {
        if (!result.hasDecidedAt()) throw new IllegalArgumentException("回执决策时间必填");
        return new ImpressionReceipt.Result(BudgetCommand.parseId(result.getReceiptId()), BudgetCommand.parseId(result.getQualificationId()),
                ImpressionReceipt.Status.valueOf(result.getStatus()), result.getLedgerId().isEmpty()?null:BudgetCommand.parseId(result.getLedgerId()),
                result.getCanonicalReceiptId().isEmpty()?null:BudgetCommand.parseId(result.getCanonicalReceiptId()), result.getChargedAmount(),
                result.getBudgetDate().isEmpty()?null:BudgetCommand.parseDate(result.getBudgetDate()),
                result.hasAcceptedAt()?instant(result.getAcceptedAt()):null, instant(result.getDecidedAt()));
    }
    private static Timestamp time(Instant instant) { return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build(); }
    private static Instant instant(Timestamp value) { return Instant.ofEpochSecond(value.getSeconds(), value.getNanos()); }
}
