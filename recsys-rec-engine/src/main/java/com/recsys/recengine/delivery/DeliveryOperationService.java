package com.recsys.recengine.delivery;

import com.recsys.ad.AdProperties;
import com.recsys.common.ad.delivery.SandboxDelivery.*;
import com.recsys.common.query.QueryUnderstandingService;
import com.recsys.recengine.ad.AdServingGateway;
import com.recsys.recengine.delivery.DeliveryOperation.*;
import com.recsys.recengine.experiment.ExperimentService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 整次请求恢复编排；所有 RPC 在数据库语句之外，部分失败不重排已冻结位置。 */
public class DeliveryOperationService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DeliveryOperationService.class);
    private final DeliveryOperationRepository repository;
    private final QueryUnderstandingService queries;
    private final ExperimentService experiments;
    private final AdProperties properties;
    private final AdServingGateway gateway;
    public DeliveryOperationService(DeliveryOperationRepository repository, QueryUnderstandingService queries,
            ExperimentService experiments, AdProperties properties, AdServingGateway gateway) {
        this.repository = repository; this.queries = queries; this.experiments = experiments;
        this.properties = properties; this.gateway = gateway;
    }

    /** PUT 原 ID 恢复；已有 COMPLETE 完全由持久化快照返回。 */
    public DeliveryOperation deliver(Request request) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("整次交付不能持有外层数据库事务调用远端 owner");
        }
        var previous = repository.find(request.operationId(), request.subject());
        if (previous.isPresent() && !previous.get().request().equals(request)) throw new Conflict();
        if (previous.isEmpty()) {
            var variant = experiments.adVariant(request.userId());
            String bucket = variant == null ? "default" : variant.getName();
            double reserve = properties.getAuction().getReservePrice();
            if (variant != null && variant.getParams().containsKey("reserve-price")) reserve = Double.parseDouble(variant.getParams().get("reserve-price"));
            repository.create(request, bucket, reserve);
        }
        var claimed = repository.acquire(request.operationId(), request.subject());
        if (claimed.isEmpty()) return current(request);
        var operation = claimed.get();
        try {
            if (operation.plan() == null) {
                var candidates = gateway.sandboxCandidates(new Query(request.operationId(), request.subject(), request.userId(),
                        queries.parse(request.query(), request.userId()), request.slots(), request.scene(), operation.bucket(), operation.reserve()));
                // 搜索页先做广告和 item 去重，feed 的自然/广告混排由 S10 调用同一准备能力。
                var adIds = new HashSet<Long>(); var itemIds = new HashSet<Long>(); var childIds = new HashSet<UUID>();
                var plan = new ArrayList<Entry>();
                for (var candidate : candidates) {
                    if (plan.size() == request.slots()) break;
                    if (!adIds.add(candidate.ad().adId()) || !itemIds.add(candidate.ad().itemId()) || !childIds.add(candidate.childId())) continue;
                    plan.add(new Entry(plan.size() + 1, candidate, null));
                }
                if (!repository.freeze(operation, plan)) return current(request);
                operation = current(request);
                // 不能取另一个执行器的 fence 继续写；此处仅接受自身仍持有的领取版本。
                if (operation.fence() != claimed.get().fence()) return operation;
            }
            if (operation.plan().isEmpty()) {
                repository.progress(operation, List.of());
                return current(request);
            }
            for (int i = 0; i < operation.plan().size(); i++) {
                var entry = operation.plan().get(i);
                if (entry.result() != null) continue;
                var prepared = gateway.findSandboxPreparation(entry.candidate().childId(), request.subject()).orElseGet(() ->
                        gateway.prepareSandbox(new Prepare(request.operationId(), request.subject(), request.userId(),
                                entry.candidate().childId(), entry.slot(), entry.candidate().offerToken())));
                var updated = new ArrayList<>(operation.plan());
                updated.set(i, new Entry(entry.slot(), entry.candidate(), prepared));
                if (!repository.progress(operation, updated)) return current(request);
                operation = current(request);
                if (operation.fence() != claimed.get().fence()) return operation;
            }
            return current(request);
        } catch (RuntimeException ex) {
            LOG.warn("sandbox delivery incomplete operationId={} failureType={}", request.operationId(), ex.getClass().getSimpleName());
            throw ex;
        } finally {
            repository.relinquish(request.operationId(), claimed.get().fence());
        }
    }

    /** GET 只读进度，不冒充未完成请求为成功空结果。 */
    public Optional<DeliveryOperation> find(UUID id, String subject) { return repository.find(id, subject); }
    private DeliveryOperation current(Request request) { return repository.find(request.operationId(), request.subject()).orElseThrow(); }
}
