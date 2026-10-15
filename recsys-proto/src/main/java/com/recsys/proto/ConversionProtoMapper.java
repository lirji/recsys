package com.recsys.proto;

import com.google.protobuf.Timestamp;
import com.recsys.common.ad.billing.BudgetCommand;
import com.recsys.common.ad.billing.ConversionSettlement.*;
import com.recsys.proto.conversion.v1.*;
import java.time.Instant;
import java.util.UUID;

/** CPA边界显式映射：原决定和当前状态独立，无金额double或零值替代null。 */
public final class ConversionProtoMapper {
    private ConversionProtoMapper() {}
    /** 主体由认证engine注入，owner重新校验字段。 */
    public static ConversionVisibleCommand toProto(VisibleCommand c) {
        return ConversionVisibleCommand.newBuilder().setReceiptId(c.receiptId().toString()).setSubject(c.subject())
                .setQualificationToken(c.qualificationToken()).setVisibleDurationMs(c.visibleDurationMs())
                .setMinVisibleRatioBps(c.minVisibleRatioBps()).setPageVisible(c.pageVisible()).build();
    }
    /** 只映射可见观测，不接受金额或广告主身份。 */
    public static VisibleCommand fromProto(ConversionVisibleCommand c) {
        return new VisibleCommand(BudgetCommand.parseId(c.getReceiptId()),c.getSubject(),c.getQualificationToken(),
                c.getVisibleDurationMs(),c.getMinVisibleRatioBps(),c.getPageVisible());
    }
    /** 不可变准备结果的期限与token逐字段保留。 */
    public static ConversionPrepared toProto(Prepared p) {
        var b=ConversionPrepared.newBuilder().setQualificationId(p.qualificationId().toString()).setDecision(p.decision().name())
                .setBudgetDate(p.budgetDate().toString()).setAmount(p.amount()).setIssuedAt(time(p.issuedAt()))
                .setExpiresAt(time(p.expiresAt())).setPreparedAt(time(p.preparedAt()));
        if(p.qualificationToken()!=null)b.setQualificationToken(p.qualificationToken());
        return b.build();
    }
    /** 缺失必需时间不允许被protobuf默认epoch掩盖。 */
    public static Prepared fromProto(ConversionPrepared p) {
        if(!p.hasIssuedAt() || !p.hasExpiresAt() || !p.hasPreparedAt())throw new IllegalArgumentException("CPA准备时间缺失");
        return new Prepared(BudgetCommand.parseId(p.getQualificationId()),Decision.valueOf(p.getDecision()),BudgetCommand.parseDate(p.getBudgetDate()),
                p.getAmount(),instant(p.getIssuedAt()),instant(p.getExpiresAt()),instant(p.getPreparedAt()),p.hasQualificationToken()?p.getQualificationToken():null);
    }
    /** 等待/释放无chargedAmount，合法零元捕获仍为显式六位金额。 */
    public static ConversionSettlementSnapshot toProto(Snapshot s) {
        var b=ConversionSettlementSnapshot.newBuilder().setPrepared(toProto(s.prepared())).setState(s.state().name()).setVersion(s.version())
                .setVisibleReceiptId(id(s.visibleReceiptId())).setOutcomeId(id(s.outcomeId())).setLedgerId(id(s.ledgerId()));
        if(s.chargedAmount()!=null)b.setChargedAmount(s.chargedAmount());
        if(s.closedAt()!=null)b.setClosedAt(time(s.closedAt()));
        if(s.closeReason()!=null)b.setCloseReason(s.closeReason().name());
        return b.build();
    }
    /** 构造领域快照时再次校验终态、账本和双条件的一致性。 */
    public static Snapshot fromProto(ConversionSettlementSnapshot s) {
        if(!s.hasPrepared())throw new IllegalArgumentException("CPA准备缺失");
        return new Snapshot(fromProto(s.getPrepared()),State.valueOf(s.getState()),s.getVersion(),optionalId(s.getVisibleReceiptId()),
                optionalId(s.getOutcomeId()),optionalId(s.getLedgerId()),s.hasChargedAmount()?s.getChargedAmount():null,
                s.hasClosedAt()?instant(s.getClosedAt()):null,s.getCloseReason().isEmpty()?null:CloseReason.valueOf(s.getCloseReason()));
    }
    /** ACK保持首次决定，当前状态另外传送。 */
    public static ConversionVisibleResult toProto(VisibleResult result) {
        var r=result.receipt();
        var b=ConversionVisibleReceipt.newBuilder().setReceiptId(r.receiptId().toString()).setQualificationId(r.qualificationId().toString())
                .setStatus(r.status().name()).setCanonicalReceiptId(id(r.canonicalReceiptId())).setDecidedAt(time(r.decidedAt()));
        if(r.acceptedAt()!=null)b.setAcceptedAt(time(r.acceptedAt()));
        return ConversionVisibleResult.newBuilder().setReceipt(b).setSettlement(toProto(result.settlement())).build();
    }
    /** 回执必须与附带资金快照属于同一资格。 */
    public static VisibleResult fromProto(ConversionVisibleResult result) {
        if(!result.hasReceipt() || !result.hasSettlement() || !result.getReceipt().hasDecidedAt())throw new IllegalArgumentException("CPA结果缺失");
        var r=result.getReceipt();
        var receipt=new VisibleReceipt(BudgetCommand.parseId(r.getReceiptId()),BudgetCommand.parseId(r.getQualificationId()),
                VisibleStatus.valueOf(r.getStatus()),optionalId(r.getCanonicalReceiptId()),r.hasAcceptedAt()?instant(r.getAcceptedAt()):null,instant(r.getDecidedAt()));
        var snapshot=fromProto(result.getSettlement());
        if(!receipt.qualificationId().equals(snapshot.prepared().qualificationId()))throw new IllegalArgumentException("CPA结果身份不一致");
        return new VisibleResult(receipt,snapshot);
    }
    /** 业务事件的actor来自已授权advertiser边界，微秒精度由领域命令复核。 */
    public static ConversionEventCommand toProto(ConversionCommand c) {
        return ConversionEventCommand.newBuilder().setAdvertiserId(c.advertiserId()).setEventId(c.eventId().toString()).setActor(c.actor())
                .setQualificationToken(c.qualificationToken()).setOccurredAt(time(c.occurredAt())).build();
    }
    /** 缺失发生时间不得以epoch默认值进入业务事实。 */
    public static ConversionCommand fromProto(ConversionEventCommand c) {
        if(!c.hasOccurredAt())throw new IllegalArgumentException("CPA发生时间缺失");
        return new ConversionCommand(c.getAdvertiserId(),BudgetCommand.parseId(c.getEventId()),c.getActor(),c.getQualificationToken(),instant(c.getOccurredAt()));
    }
    /** 原业务决定和当前资格状态逐字段传送，不把重复业务事件视为再次扣款。 */
    public static ConversionOutcomeResult toProto(OutcomeResult value) {
        var o=value.outcome();var b=ConversionOutcome.newBuilder().setOutcomeId(o.outcomeId().toString()).setAdvertiserId(o.advertiserId())
                .setEventId(o.eventId().toString()).setQualificationId(o.qualificationId().toString()).setOccurredAt(time(o.occurredAt()))
                .setReceivedAt(time(o.receivedAt())).setStatus(o.status().name()).setCanonicalOutcomeId(id(o.canonicalOutcomeId())).setDecidedAt(time(o.decidedAt()));
        return ConversionOutcomeResult.newBuilder().setOutcome(b).setSettlement(toProto(value.settlement())).build();
    }
    /** owner结果必须包含同资格完整时间与状态；不接受孤立业务事实替代结算结果。 */
    public static OutcomeResult fromProto(ConversionOutcomeResult value) {
        if(!value.hasOutcome() || !value.hasSettlement())throw new IllegalArgumentException("CPA业务结果缺失");
        var o=value.getOutcome();
        if(!o.hasOccurredAt() || !o.hasReceivedAt() || !o.hasDecidedAt() || o.getOccurredAt().getNanos()%1000!=0)throw new IllegalArgumentException("CPA业务时间缺失");
        var outcome=new Outcome(BudgetCommand.parseId(o.getOutcomeId()),o.getAdvertiserId(),BudgetCommand.parseId(o.getEventId()),BudgetCommand.parseId(o.getQualificationId()),
                instant(o.getOccurredAt()),instant(o.getReceivedAt()),OutcomeStatus.valueOf(o.getStatus()),optionalId(o.getCanonicalOutcomeId()),instant(o.getDecidedAt()));
        var snapshot=fromProto(value.getSettlement());
        if(!outcome.qualificationId().equals(snapshot.prepared().qualificationId()))throw new IllegalArgumentException("CPA业务结果资格不一致");
        return new OutcomeResult(outcome,snapshot);
    }
    private static String id(UUID value) { return value==null?"":value.toString(); }
    private static UUID optionalId(String value) { return value.isEmpty()?null:BudgetCommand.parseId(value); }
    private static Timestamp time(Instant value) { return Timestamp.newBuilder().setSeconds(value.getEpochSecond()).setNanos(value.getNano()).build(); }
    private static Instant instant(Timestamp value) {
        if(value.getNanos()<0 || value.getNanos()>999999999)throw new IllegalArgumentException("时间纳秒非法");
        return Instant.ofEpochSecond(value.getSeconds(),value.getNanos());
    }
}
