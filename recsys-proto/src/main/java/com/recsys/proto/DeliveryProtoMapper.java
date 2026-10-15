package com.recsys.proto;

import com.google.protobuf.Timestamp;
import com.recsys.common.ad.billing.BudgetCommand;
import com.recsys.common.ad.delivery.SandboxDelivery.*;
import com.recsys.proto.delivery.v1.*;
import java.time.Instant;

/** 双模式共享的有版本 wire 映射；金额不经过 double，未知状态拒绝解析。 */
public final class DeliveryProtoMapper {
    private DeliveryProtoMapper() {}
    /** 候选查询携带已解析 query，不在 owner 重新分桶。 */
    public static CandidateQuery toProto(Query q) {
        return CandidateQuery.newBuilder().setOperationId(q.operationId().toString()).setSubject(q.subject()).setUserId(q.userId())
                .setQuery(AdProtoMapper.toProto(q.query())).setSlots(q.slots()).setScene(q.scene()).setBucket(q.bucket()).setReserve(q.reserve()).build();
    }
    /** 候选输入复用领域校验，非法边界不得进入 owner。 */
    public static Query fromProto(CandidateQuery q) {
        return new Query(BudgetCommand.parseId(q.getOperationId()), q.getSubject(), q.getUserId(), AdProtoMapper.fromProto(q.getQuery()),
                q.getSlots(), q.getScene(), q.getBucket(), q.getReserve());
    }
    /** 展示快照与签名证明共同传递，供 engine 冻结。 */
    public static DeliveryCandidate toProto(Candidate c) {
        return DeliveryCandidate.newBuilder().setChildId(c.childId().toString()).setAd(AdProtoMapper.toProto(c.ad())).setOfferToken(c.offerToken()).build();
    }
    /** 保留 owner 生成的子 ID，不能在消费者重新生成。 */
    public static Candidate fromProto(DeliveryCandidate c) {
        return new Candidate(BudgetCommand.parseId(c.getChildId()), AdProtoMapper.fromProto(c.getAd()), c.getOfferToken());
    }
    /** 恢复必须发送同一位置和原始证明。 */
    public static PrepareDelivery toProto(Prepare p) {
        return PrepareDelivery.newBuilder().setOperationId(p.operationId().toString()).setSubject(p.subject()).setUserId(p.userId())
                .setChildId(p.childId().toString()).setSlot(p.slot()).setOfferToken(p.offerToken()).build();
    }
    /** 以稳定 UUID 与主体构造准备命令。 */
    public static Prepare fromProto(PrepareDelivery p) {
        return new Prepare(BudgetCommand.parseId(p.getOperationId()), p.getSubject(), p.getUserId(), BudgetCommand.parseId(p.getChildId()), p.getSlot(), p.getOfferToken());
    }
    /** 原时间保留纳秒，不用毫秒往返改变到期边界。 */
    public static PreparedDelivery toProto(Prepared p) {
        var b = PreparedDelivery.newBuilder().setChildId(p.childId().toString()).setStatus(p.status().name())
                .setAmount(p.amount()).setQualificationToken(p.qualificationToken());
        if (p.expiresAt() != null) b.setExpiresAt(Timestamp.newBuilder().setSeconds(p.expiresAt().getEpochSecond()).setNanos(p.expiresAt().getNano()));
        return b.build();
    }
    /** 拒绝未知状态，保留 owner 的精确金额和原期限。 */
    public static Prepared fromProto(PreparedDelivery p) {
        return new Prepared(BudgetCommand.parseId(p.getChildId()), Status.valueOf(p.getStatus()), p.getAmount(), p.getQualificationToken(),
                p.hasExpiresAt() ? Instant.ofEpochSecond(p.getExpiresAt().getSeconds(), p.getExpiresAt().getNanos()) : null);
    }
}
