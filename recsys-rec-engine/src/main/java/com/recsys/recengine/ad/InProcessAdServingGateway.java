package com.recsys.recengine.ad;

import com.recsys.common.ad.delivery.SandboxDelivery.Candidate;
import com.recsys.common.ad.delivery.SandboxDelivery.Query;
import com.recsys.common.ad.delivery.SandboxDelivery.Prepared;
import com.recsys.common.ad.delivery.SandboxDelivery.Prepare;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.recsys.ad.billing.SandboxPreparationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import com.recsys.ad.AdPipeline;
import com.recsys.ad.billing.ImpressionReceiptService;
import com.recsys.common.ad.billing.ImpressionReceipt;
import com.recsys.common.ad.SearchAdsResponse;
import com.recsys.common.query.StructuredQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.Instant;

/**
 * 默认(单体回退)实现:进程内直调 {@link AdPipeline}(recsys-ad lib,与广告在线服务共用同一份管线代码)。
 * 行为与拆分前的单体逐行等价——广告链路仍在 rec-engine 进程内,零网络跳、零 gRPC。
 *
 * <p>{@code recsys.ad.serving.mode} 缺省或 = {@code in-process} 时生效({@code matchIfMissing=true}),
 * 是绞杀者迁移的安全默认与回滚落点。
 */
@Component
@ConditionalOnProperty(name = "recsys.ad.serving.mode", havingValue = "in-process", matchIfMissing = true)
public class InProcessAdServingGateway implements AdServingGateway {

    private final AdPipeline pipeline;

    public InProcessAdServingGateway(AdPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Autowired
    private ObjectProvider<SandboxPreparationService> sandbox;

    /** 仅启用新 owner 时可用，未启用不回落到旧扣费管线。 */
    private SandboxPreparationService sandbox() {
        var service = sandbox == null ? null : sandbox.getIfAvailable();
        if (service == null) throw new IllegalStateException("sandbox 交付未启用");
        return service;
    }
    @Override
    public List<Candidate> sandboxCandidates(
            Query query) { return sandbox().candidates(query); }
    @Override
    public Prepared prepareSandbox(
            Prepare request) { return sandbox().prepare(request); }
    @Override
    public Optional<Prepared> findSandboxPreparation(UUID id, String subject) {
        return sandbox().find(id, subject);
    }

    @Autowired
    private ObjectProvider<com.recsys.ad.billing.FeedCandidateService> feedCandidates;

    /** 未启用新准入只向 engine 报不可用，不借用旧 Redis 计数放行。 */
    @Override
    public List<Candidate> sandboxFeedCandidates(Query query) {
        var service=feedCandidates==null?null:feedCandidates.getIfAvailable();
        if(service==null) throw new IllegalStateException("sandbox feed 候选未启用");
        return service.candidates(query);
    }

    @Autowired
    private ObjectProvider<ImpressionReceiptService> impressions;

    /** 回执开关关闭时拒绝调用，不能回落到旧计费。 */
    private ImpressionReceiptService impressions() {
        var service = impressions == null ? null : impressions.getIfAvailable();
        if (service == null) throw new IllegalStateException("sandbox 回执未启用");
        return service;
    }
    @Override
    public ImpressionReceipt.Result acceptSandboxImpression(ImpressionReceipt.Command command) { return impressions().accept(command); }
    @Override
    public Optional<ImpressionReceipt.Result> findSandboxImpression(UUID id, String subject) { return impressions().find(id, subject); }

    @Autowired private ObjectProvider<com.recsys.ad.billing.ClickPreparationService> clickPreparations;
    @Autowired private ObjectProvider<com.recsys.ad.billing.ClickSettlementService> clickSettlements;
    private com.recsys.ad.billing.ClickPreparationService clickPreparations() {
        var service=clickPreparations==null?null:clickPreparations.getIfAvailable();
        if(service==null)throw new IllegalStateException("点击交付未启用");
        return service;
    }
    private com.recsys.ad.billing.ClickSettlementService clickSettlements() {
        var service=clickSettlements==null?null:clickSettlements.getIfAvailable();
        if(service==null)throw new IllegalStateException("点击结算未启用");
        return service;
    }
    /** 新能力缺失直接失败，不转旧Redis点击路径。 */
    @Override public List<Candidate> sandboxClickCandidates(Query query) { return clickPreparations().candidates(query); }
    @Override public Prepared prepareSandboxClick(Prepare request) { return clickPreparations().prepare(request); }
    @Override public Optional<Prepared> findSandboxClickPreparation(UUID id,String subject) { return clickPreparations().find(id,subject); }
    @Override public com.recsys.common.ad.billing.ClickSettlement.Result acceptSandboxClick(com.recsys.common.ad.billing.ClickSettlement.Command command) {
        return clickSettlements().accept(command);
    }
    @Override public Optional<com.recsys.common.ad.billing.ClickSettlement.Result> findSandboxClickReceipt(UUID id,String subject) {
        return clickSettlements().findReceipt(id,subject);
    }
    @Override public Optional<com.recsys.common.ad.billing.ClickSettlement.Snapshot> findSandboxClickSettlement(UUID id,String subject) {
        return clickSettlements().find(id,subject);
    }

    @Override
    public SearchAdsResponse searchAds(long userId, StructuredQuery sq, int slots,
                                       String scene, String adBucket, double reserve) {
        return pipeline.run(userId, sq, slots, scene, adBucket, reserve);
    }

    @Override
    public void recordClick(String requestId, long adId, long userId) {
        pipeline.recordClick(requestId, adId, userId);
    }

    @Override
    public void recordConversion(String requestId, long adId, long userId) {
        pipeline.recordConversion(requestId, adId, userId);
    }

    @Override
    public boolean recordOutcome(String eventId, long advertiserId, long userId,
                                 String objective, double value, Instant occurredAt) {
        return pipeline.recordOutcome(eventId, advertiserId, userId, objective, value, occurredAt);
    }
}
