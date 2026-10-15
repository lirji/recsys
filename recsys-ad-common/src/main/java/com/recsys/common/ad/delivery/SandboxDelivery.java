package com.recsys.common.ad.delivery;

import com.recsys.common.ad.SponsoredAd;
import com.recsys.common.ad.billing.CpmPrice;
import com.recsys.common.ad.billing.Reservation;
import com.recsys.common.query.StructuredQuery;
import java.time.Instant;
import java.util.UUID;

/** 新交付边界的稳定契约；价格只能来自 owner 签名候选，终端不能提交。 */
public final class SandboxDelivery {
    public static final String POLICY = "IMAGE_TEXT_50_PERCENT_1000MS_HIDDEN_RESET_V1";
    public static final int MAX_SLOTS = 10;
    private SandboxDelivery() {}

    /** 仅供服务端装配选择独立检查点和owner，不作为客户端任意路由参数。 */
    public enum ChargeMode { IMPRESSION, CLICK }

    /** 已经由 engine 解析的纯查询上下文，subject 与模拟用户分离。 */
    public record Query(UUID operationId, String subject, long userId, StructuredQuery query,
                        int slots, String scene, String bucket, double reserve) {
        public Query {
            requireIdentity(operationId, subject, userId);
            requireSlot(slots);
            if (query == null || scene == null || scene.isBlank() || scene.length() > 100
                    || bucket == null || bucket.length() > 200) throw new IllegalArgumentException("候选查询不完整");
            CpmPrice.decimal(reserve);
        }
    }

    /** engine 冻结显示快照与原证明；证明不暴露在对外 HTTP 响应。 */
    public record Candidate(UUID childId, SponsoredAd ad, String offerToken) {
        public Candidate {
            if (childId == null || ad == null) throw new IllegalArgumentException("候选标识缺失");
            requireToken(offerToken);
        }
    }

    /** 只有签名 owner 解码价格；期限以 owner 数据库时间验证。 */
    public record Offer(UUID operationId, String subject, long userId, UUID childId, SponsoredAd ad,
                        CpmPrice price, String bucket, Instant expiresAt) {
        public Offer {
            requireIdentity(operationId, subject, userId);
            if (childId == null || ad == null || price == null || expiresAt == null || bucket == null
                    || ad.advertiserId() <= 0 || ad.adId() <= 0 || !com.recsys.common.ad.BidType.CPM.name().equals(ad.bidType())) {
                throw new IllegalArgumentException("候选证明不完整");
            }
        }
    }

    /** 最终位置由可信 engine 冻结，同 childId 不允许换位置。 */
    public record Prepare(UUID operationId, String subject, long userId, UUID childId, int slot, String offerToken) {
        public Prepare {
            requireIdentity(operationId, subject, userId);
            requireSlot(slot);
            requireToken(offerToken);
            if (childId == null) throw new IllegalArgumentException("childId 必填");
        }
    }

    /** 首次准备结论，QUALIFIED 仍不是已曝光或已收费。 */
    public enum Status { QUALIFIED, INSUFFICIENT_BUDGET, QUOTE_EXPIRED, ACCOUNT_MISSING, OFFER_EXPIRED }

    /** 不可变原结果，金额保持十进制字符串，未知结果不得拼成拒绝或空成功。 */
    public record Prepared(UUID childId, Status status, String amount, String qualificationToken, Instant expiresAt) {
        public Prepared {
            if (childId == null || status == null || amount == null || qualificationToken == null) {
                throw new IllegalArgumentException("准备结果不完整");
            }
            if (status == Status.QUALIFIED) {
                com.recsys.common.ad.billing.CnyAmount.parse(amount);
                requireToken(qualificationToken);
                if (expiresAt == null) throw new IllegalArgumentException("资格期限必填");
            }
        }
    }

    /** 资格载荷与持久化预占绑定，后续回执必须同时验证签名和数据库当前状态。 */
    public record Qualification(UUID operationId, UUID childId, String subject, long userId,
                                long advertiserId, long adId, long creativeId, int slot, String amount,
                                String trafficClass, String currency, String policy, Instant issuedAt, Instant expiresAt) {
        public Qualification {
            requireIdentity(operationId, subject, userId); requireSlot(slot);
            com.recsys.common.ad.billing.CnyAmount.parse(amount);
            if (childId == null || advertiserId <= 0 || adId <= 0 || creativeId < 0 || issuedAt == null || expiresAt == null
                    || !expiresAt.equals(issuedAt.plusSeconds(com.recsys.common.ad.billing.FrozenCpmQuote.TTL_SECONDS))
                    || trafficClass == null || currency == null || policy == null) throw new IllegalArgumentException("资格快照不完整");
        }
    }

    /** 权限失败与负载冲突单独映射，不能归并为普通空候选。 */
    public static final class Conflict extends RuntimeException {
        public Conflict() { super("交付标识已绑定不同请求"); }
    }

    /** 对外错误不得携带 token 原文。 */
    public static final class InvalidProof extends RuntimeException {
        public InvalidProof() { super("候选或资格证明无效"); }
    }

    /** 主体来自认证上下文，模拟 userId 不能替代访问权限。 */
    public static void requireIdentity(UUID operationId, String subject, long userId) {
        Reservation.validateSubject(subject);
        if (operationId == null || userId <= 0) throw new IllegalArgumentException("交付 ID 和正整数 userId 必填");
    }

    /** 限制单操作资源和最终位置范围。 */
    public static void requireSlot(int slot) {
        if (slot < 1 || slot > MAX_SLOTS) throw new IllegalArgumentException("sandbox 位次须为 1～10");
    }

    /** 在解码前限制证明长度，避免无界载荷。 */
    public static void requireToken(String token) {
        if (token == null || token.isBlank() || token.length() > 16384) throw new IllegalArgumentException("证明缺失或过长");
    }
}
