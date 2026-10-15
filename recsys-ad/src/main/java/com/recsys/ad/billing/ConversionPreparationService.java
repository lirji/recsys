package com.recsys.ad.billing;

import com.recsys.ad.AdPipeline;
import com.recsys.ad.delivery.DeliveryTokenCodec;
import com.recsys.common.ad.delivery.ConversionDelivery;
import com.recsys.common.ad.delivery.SandboxDelivery.*;
import com.recsys.common.ad.billing.ConversionSettlement;
import com.recsys.common.ad.billing.FrozenConversionQuote;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** CPA交付owner：先固定请求，再依次恢复报价/预占/原资格，不跨阶段持有数据库事务。 */
public class ConversionPreparationService {
    private final AdPipeline pipeline;
    private final PreparationRepository repository;
    private final ConversionQuoteRepository quotes;
    private final ConversionSettlementService settlements;
    private final DeliveryTokenCodec tokens;
    private final boolean admission;
    public ConversionPreparationService(AdPipeline pipeline,PreparationRepository repository,ConversionQuoteRepository quotes,
            ConversionSettlementService settlements,DeliveryTokenCodec tokens,boolean admission) {
        if(repository.mode()!=ChargeMode.CONVERSION || repository.dataSource()!=quotes.dataSource() || repository.dataSource()!=settlements.dataSource()) {
            throw new IllegalArgumentException("CPA准备必须使用独立模式及同一adDb资源");
        }
        this.pipeline=pipeline;this.repository=repository;this.quotes=quotes;this.settlements=settlements;this.tokens=tokens;this.admission=admission;
    }
    /** 纯候选不会创建报价或held；暂停新增时不签发新的offer。 */
    public List<Candidate> candidates(Query query) {
        if(!admission) throw new IllegalStateException("新增CPA交付已暂停");
        var expiry=repository.now().plusSeconds(FrozenConversionQuote.TTL_SECONDS);
        return pipeline.sandboxConversionCandidates(query.userId(),query.query(),query.slots(),query.scene(),query.reserve()).stream().map(q->{
            var id=UUID.randomUUID();
            var offer=new ConversionDelivery.Offer(query.operationId(),query.subject(),query.userId(),id,q.ad(),q.price(),query.bucket(),expiry);
            return new Candidate(id,q.ad(),tokens.sign(ConversionDelivery.OFFER_PURPOSE,offer));
        }).toList();
    }
    /** 已持久化首次结果在密钥轮换后可恢复；首次请求仍须用途与身份验签。 */
    public Prepared prepare(Prepare request) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("CPA准备不能加入外层事务");
        }
        var original=repository.original(request);
        if(original.isPresent()) return original.get();
        var offer=tokens.verify(request.offerToken(),ConversionDelivery.OFFER_PURPOSE,ConversionDelivery.Offer.class);
        if(!offer.operationId().equals(request.operationId()) || !offer.childId().equals(request.childId())
                || !offer.subject().equals(request.subject()) || offer.userId()!=request.userId()) throw new InvalidProof();
        // 已有预占可完成检查点；暂停新增不得产生新的报价或held。
        if(!admission && settlements.find(request.childId(),request.subject()).isEmpty()) throw new IllegalStateException("新增CPA交付已暂停");
        repository.claim(request);
        var a=offer.ad();
        var quote=quotes.freezeBefore(new ConversionQuoteRepository.Draft(request.childId(),request.operationId(),request.subject(),request.userId(),
                a.advertiserId(),a.adId(),a.creativeId(),a.itemId(),request.slot(),a.title(),offer.price()),offer.expiresAt());
        if(quote.isEmpty()) return repository.complete(new Prepared(request.childId(),Status.OFFER_EXPIRED,"","",null),request.subject());
        var prepared=settlements.prepare(request.childId(),request.subject());
        var status=prepared.decision()==ConversionSettlement.Decision.HELD?Status.QUALIFIED:Status.valueOf(prepared.decision().name());
        return repository.complete(new Prepared(request.childId(),status,prepared.amount(),
                prepared.qualificationToken()==null?"":prepared.qualificationToken(),prepared.expiresAt()),request.subject());
    }
    /** 原结果查询不重新签名或刷新期限，调用方仍须查询当前资金状态。 */
    public Optional<Prepared> find(UUID id,String subject) { return repository.find(id,subject); }
}
