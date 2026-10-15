package com.recsys.proto;

import com.google.protobuf.Timestamp;
import com.recsys.common.ad.billing.BudgetCommand;
import com.recsys.common.ad.billing.ClickSettlement.*;
import com.recsys.proto.click.v1.*;
import java.time.Instant;
import java.util.UUID;

/** 点击边界显式映射：原决定和当前状态独立，无金额double或零值替代null。 */
public final class ClickProtoMapper {
    private ClickProtoMapper() {}
    /** 主体由认证engine注入，owner重新校验字段。 */
    public static ClickReceiptCommand toProto(Command c) {
        return ClickReceiptCommand.newBuilder().setReceiptId(c.receiptId().toString()).setSubject(c.subject())
                .setQualificationToken(c.qualificationToken()).setKind(c.kind().name()).setVisibleDurationMs(c.visibleDurationMs())
                .setMinVisibleRatioBps(c.minVisibleRatioBps()).setPageVisible(c.pageVisible()).build();
    }
    /** 未知种类严格拒绝，不能静默变成曝光或点击。 */
    public static Command fromProto(ClickReceiptCommand c) {
        return new Command(BudgetCommand.parseId(c.getReceiptId()),c.getSubject(),c.getQualificationToken(),Kind.valueOf(c.getKind()),
                c.getVisibleDurationMs(),c.getMinVisibleRatioBps(),c.getPageVisible());
    }
    /** 不可变准备结果的期限与token逐字段保留。 */
    public static ClickPrepared toProto(Prepared p) {
        var b=ClickPrepared.newBuilder().setQualificationId(p.qualificationId().toString()).setDecision(p.decision().name())
                .setBudgetDate(p.budgetDate().toString()).setAmount(p.amount()).setIssuedAt(time(p.issuedAt()))
                .setExpiresAt(time(p.expiresAt())).setPreparedAt(time(p.preparedAt()));
        if(p.qualificationToken()!=null)b.setQualificationToken(p.qualificationToken());
        return b.build();
    }
    /** 缺失必需时间不允许被protobuf默认epoch掩盖。 */
    public static Prepared fromProto(ClickPrepared p) {
        if(!p.hasIssuedAt() || !p.hasExpiresAt() || !p.hasPreparedAt())throw new IllegalArgumentException("点击准备时间缺失");
        return new Prepared(BudgetCommand.parseId(p.getQualificationId()),Decision.valueOf(p.getDecision()),BudgetCommand.parseDate(p.getBudgetDate()),
                p.getAmount(),instant(p.getIssuedAt()),instant(p.getExpiresAt()),instant(p.getPreparedAt()),p.hasQualificationToken()?p.getQualificationToken():null);
    }
    /** 等待/释放无chargedAmount，合法零元捕获仍为显式六位金额。 */
    public static ClickSettlementSnapshot toProto(Snapshot s) {
        var b=ClickSettlementSnapshot.newBuilder().setPrepared(toProto(s.prepared())).setState(s.state().name()).setVersion(s.version())
                .setVisibleReceiptId(id(s.visibleReceiptId())).setClickReceiptId(id(s.clickReceiptId())).setLedgerId(id(s.ledgerId()));
        if(s.chargedAmount()!=null)b.setChargedAmount(s.chargedAmount());
        if(s.closedAt()!=null)b.setClosedAt(time(s.closedAt()));
        if(s.closeReason()!=null)b.setCloseReason(s.closeReason().name());
        return b.build();
    }
    /** 构造领域快照时再次校验终态、账本和双条件的一致性。 */
    public static Snapshot fromProto(ClickSettlementSnapshot s) {
        if(!s.hasPrepared())throw new IllegalArgumentException("点击准备缺失");
        return new Snapshot(fromProto(s.getPrepared()),State.valueOf(s.getState()),s.getVersion(),optionalId(s.getVisibleReceiptId()),
                optionalId(s.getClickReceiptId()),optionalId(s.getLedgerId()),s.hasChargedAmount()?s.getChargedAmount():null,
                s.hasClosedAt()?instant(s.getClosedAt()):null,s.getCloseReason().isEmpty()?null:CloseReason.valueOf(s.getCloseReason()));
    }
    /** ACK保持首次决定，当前状态另外传送。 */
    public static ClickReceiptResult toProto(Result result) {
        var r=result.receipt();
        var b=ClickReceipt.newBuilder().setReceiptId(r.receiptId().toString()).setQualificationId(r.qualificationId().toString())
                .setKind(r.kind().name()).setStatus(r.status().name()).setCanonicalReceiptId(id(r.canonicalReceiptId())).setDecidedAt(time(r.decidedAt()));
        if(r.acceptedAt()!=null)b.setAcceptedAt(time(r.acceptedAt()));
        return ClickReceiptResult.newBuilder().setReceipt(b).setSettlement(toProto(result.settlement())).build();
    }
    /** 回执必须与附带资金快照属于同一资格。 */
    public static Result fromProto(ClickReceiptResult result) {
        if(!result.hasReceipt() || !result.hasSettlement() || !result.getReceipt().hasDecidedAt())throw new IllegalArgumentException("点击结果缺失");
        var r=result.getReceipt();
        var receipt=new Receipt(BudgetCommand.parseId(r.getReceiptId()),BudgetCommand.parseId(r.getQualificationId()),Kind.valueOf(r.getKind()),
                ReceiptStatus.valueOf(r.getStatus()),optionalId(r.getCanonicalReceiptId()),r.hasAcceptedAt()?instant(r.getAcceptedAt()):null,instant(r.getDecidedAt()));
        var snapshot=fromProto(result.getSettlement());
        if(!receipt.qualificationId().equals(snapshot.prepared().qualificationId()))throw new IllegalArgumentException("点击结果身份不一致");
        return new Result(receipt,snapshot);
    }
    private static String id(UUID value) { return value==null?"":value.toString(); }
    private static UUID optionalId(String value) { return value.isEmpty()?null:BudgetCommand.parseId(value); }
    private static Timestamp time(Instant value) { return Timestamp.newBuilder().setSeconds(value.getEpochSecond()).setNanos(value.getNano()).build(); }
    private static Instant instant(Timestamp value) {
        if(value.getNanos()<0 || value.getNanos()>999999999)throw new IllegalArgumentException("时间纳秒非法");
        return Instant.ofEpochSecond(value.getSeconds(),value.getNanos());
    }
}
