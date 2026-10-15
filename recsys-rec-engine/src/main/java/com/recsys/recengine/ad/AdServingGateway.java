package com.recsys.recengine.ad;

import com.recsys.common.ad.delivery.SandboxDelivery.Candidate;
import com.recsys.common.ad.delivery.SandboxDelivery.Query;
import com.recsys.common.ad.delivery.SandboxDelivery.Prepared;
import com.recsys.common.ad.delivery.SandboxDelivery.Prepare;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.recsys.common.ad.SearchAdsResponse;
import com.recsys.common.query.StructuredQuery;
import java.time.Instant;
import com.recsys.common.ad.billing.ImpressionReceipt;

/**
 * 广告在线服务网关(rec-engine 侧的调用抽象)。屏蔽"进程内直调 vs 跨进程 gRPC"两种实现,
 * 供绞杀者迁移期以 {@code recsys.ad.serving.mode} 一键切换与回滚。
 *
 * <p>入参已是 rec-engine 解析好的 {@link StructuredQuery} 与实验产出的 {@code adBucket}/{@code reserve}
 * ——query 理解与实验分桶是 rec-engine 单一来源,广告 supplier 只消费。
 *
 * @see InProcessAdServingGateway 默认(in-process):直调进程内 {@code AdPipeline}(recsys-ad lib),行为等同单体
 * @see GrpcAdServingGateway grpc:调 recsys-ad-serving 的 gRPC(广告在线服务独立进程)
 */
public interface AdServingGateway {

    /** 新路径纯候选，异常不得经旧空广告降级。 */
    List<Candidate> sandboxCandidates(
            Query query);

    /** feed 独立准入，不能用旧候选/旧收费 fallback 绕过可信频控。 */
    List<Candidate> sandboxFeedCandidates(Query query);

    /** 原 childId 准备，不能使用旧响应即收费路径。 */
    Prepared prepareSandbox(
            Prepare request);

    /** 已提交原结果查询；空仅代表当前未观察到。 */
    Optional<Prepared> findSandboxPreparation(UUID id, String subject);

    /** 新可见回执只通过 owner 原子捕获，不调用旧曝光/点击接口。 */
    ImpressionReceipt.Result acceptSandboxImpression(ImpressionReceipt.Command command);

    /** 同一 receiptId 的原结果查询，用于未知响应恢复。 */
    Optional<ImpressionReceipt.Result> findSandboxImpression(UUID id, String subject);

    /** 纯点击候选与准备使用独立owner及证明用途。 */
    List<Candidate> sandboxClickCandidates(Query query);
    Prepared prepareSandboxClick(Prepare request);
    Optional<Prepared> findSandboxClickPreparation(UUID id,String subject);
    /** 原子接受任一条件，等待不代表已收费。 */
    com.recsys.common.ad.billing.ClickSettlement.Result acceptSandboxClick(com.recsys.common.ad.billing.ClickSettlement.Command command);
    /** 原回执决定和当前状态一起查询。 */
    Optional<com.recsys.common.ad.billing.ClickSettlement.Result> findSandboxClickReceipt(UUID id,String subject);
    /** 资格仍有效与否以owner当前状态为准。 */
    Optional<com.recsys.common.ad.billing.ClickSettlement.Snapshot> findSandboxClickSettlement(UUID id,String subject);

    /** 搜索广告:召回→竞价→GSP→曝光埋点,返回竞得赞助广告。 */
    SearchAdsResponse searchAds(long userId, StructuredQuery sq, int slots,
                                String scene, String adBucket, double reserve);

    /** CPC/OCPC 点击计费(反作弊 + 归因校验在 supplier 侧执行)。 */
    void recordClick(String requestId, long adId, long userId);

    /** 转化回传(CPA 计费 + 延迟转化建模)。 */
    void recordConversion(String requestId, long adId, long userId);

    /** A7 独立 outcome，eventId 幂等且不依赖曝光 requestId。 */
    boolean recordOutcome(String eventId, long advertiserId, long userId,
                          String objective, double value, Instant occurredAt);
}
