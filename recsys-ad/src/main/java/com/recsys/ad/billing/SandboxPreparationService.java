package com.recsys.ad.billing;

import com.recsys.ad.AdPipeline;
import com.recsys.ad.delivery.DeliveryTokenCodec;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.common.ad.delivery.SandboxDelivery.*;
import com.recsys.common.ad.billing.Reservation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 无跨库事务的准备编排；每一步都按稳定 childId 恢复，只有回执片才会收费。 */
public class SandboxPreparationService {
    private final AdPipeline pipeline;
    private final PreparationRepository repository;
    private final CpmQuoteRepository quotes;
    private final ReservationService reservations;
    private final DeliveryTokenCodec tokens;
    public SandboxPreparationService(AdPipeline pipeline, PreparationRepository repository, CpmQuoteRepository quotes,
                                     ReservationService reservations, DeliveryTokenCodec tokens) {
        if (repository.dataSource() != quotes.dataSource() || repository.dataSource() != reservations.dataSource()) throw new IllegalArgumentException("准备和报价必须同一 adDb");
        this.pipeline = pipeline; this.repository = repository; this.quotes = quotes;
        this.reservations = reservations; this.tokens = tokens;
    }

    /** 只读候选及签名证明，不创建报价、占用、曝光或旧事件。 */
    public List<Candidate> candidates(Query query) {
        var expiry = repository.now().plusSeconds(300);
        return pipeline.sandboxCandidates(query.userId(), query.query(), query.slots(), query.scene(), query.reserve()).stream()
                .map(q -> {
                    UUID id = UUID.randomUUID();
                    var offer = new Offer(query.operationId(), query.subject(), query.userId(), id, q.ad(), q.price(), query.bucket(), expiry);
                    return new Candidate(id, q.ad(), tokens.sign("offer", offer));
                }).toList();
    }

    /** 已保存结果也校验请求一致性；后续步骤崩溃不重选广告、不重新计算报价。 */
    public Prepared prepare(Prepare request) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("准备编排不能加入外层数据库事务");
        }
        var offer = tokens.verify(request.offerToken(), "offer", Offer.class);
        if (!offer.operationId().equals(request.operationId()) || !offer.childId().equals(request.childId())
                || !offer.subject().equals(request.subject()) || offer.userId() != request.userId()) throw new InvalidProof();
        repository.claim(request);
        var prior = repository.find(request.childId(), request.subject());
        if (prior.isPresent()) return prior.get();
        var quote = quotes.freezeBefore(request.childId(), request.operationId(), offer.ad().advertiserId(),
                offer.ad().adId(), request.slot(), offer.price(), offer.expiresAt());
        if (quote.isEmpty()) return repository.complete(new Prepared(request.childId(), Status.OFFER_EXPIRED, "", "", null), request.subject());
        var held = reservations.prepare(request.childId(), request.childId(), request.subject());
        Status status = held.decision() == Reservation.Decision.HELD ? Status.QUALIFIED : Status.valueOf(held.decision().name());
        String token = "";
        if (status == Status.QUALIFIED) {
            var qualification = new Qualification(request.operationId(), request.childId(), request.subject(), request.userId(),
                    offer.ad().advertiserId(), offer.ad().adId(), offer.ad().creativeId(), request.slot(), held.amount().toPlainString(),
                    "SANDBOX", "CNY", SandboxDelivery.POLICY, quote.get().issuedAt(), quote.get().expiresAt());
            token = tokens.sign("qualification", qualification);
        }
        return repository.complete(new Prepared(request.childId(), status, held.amount().toPlainString(), token, held.expiresAt()), request.subject());
    }

    /** 查询只读原结论；过期资格不得被下游当作当前仍有效。 */
    public Optional<Prepared> find(UUID childId, String subject) { return repository.find(childId, subject); }
}
