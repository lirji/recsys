package com.recsys.recengine.exposure;

import com.recsys.common.dto.RecommendRequest;
import com.recsys.common.dto.RecommendItem;
import com.recsys.common.exposure.*;
import com.recsys.recengine.RecommendOrchestrator;
import com.recsys.recengine.content.ContentGateway;
import com.recsys.recengine.exposure.NaturalDelivery.*;
import java.util.*;

/** 最终自然交付用例；先持久化原操作，再计算和冻结资格，响应本身不计曝光。 */
public class NaturalDeliveryService {
    private final NaturalDeliveryRepository repository;
    private final RecommendOrchestrator recommender;
    private final ContentGateway content;
    private final NaturalQualificationToken tokens;
    private final NaturalQualification.Policy policy;
    private final boolean admissionEnabled;
    public NaturalDeliveryService(NaturalDeliveryRepository repository,RecommendOrchestrator recommender,ContentGateway content,
            NaturalQualificationToken tokens,NaturalQualification.Policy policy,boolean admissionEnabled) {
        this.repository=repository;this.recommender=recommender;this.content=content;this.tokens=tokens;this.policy=Objects.requireNonNull(policy);this.admissionEnabled=admissionEnabled;
    }
    /** 已完成原请求永远复用；未知/未完成只可用原 ID 恢复，不能刷新资格有效期。 */
    public View deliver(Request request) {
        var existing=repository.find(request.operationId(),request.subject());
        if(existing.isPresent()) {
            if(!existing.get().request().equals(request)) throw new NaturalReceipt.Conflict();
            if(existing.get().state()==State.COMPLETE) return existing.get().result();
        }
        if(!admissionEnabled) throw new IllegalStateException("新自然交付已暂停，原结果仍可查询");
        repository.create(request,policy);
        var acquired=repository.acquire(request.operationId(),request.subject());
        if(acquired.isEmpty()) return find(request.operationId(),request.subject()).orElseThrow();
        var operation=acquired.get();
        try {
            var candidates=recommender.naturalCandidates(new RecommendRequest(request.userId(),request.size(),request.scene(),request.query()));
            var unique=new LinkedHashMap<Long,RecommendItem>();
            for(var item:candidates.response().items()) {
                if(item.itemId()<=0) throw new IllegalStateException("候选身份非法");
                unique.putIfAbsent(item.itemId(),item); if(unique.size()==request.size()) break;
            }
            var snapshots=unique.isEmpty()?Map.<Long,com.recsys.content.Item>of():content.findByIds(List.copyOf(unique.keySet()));
            var now=repository.databaseNow(); var entries=new ArrayList<Entry>();
            for(var item:unique.values()) {
                var snapshot=snapshots.get(item.itemId());
                if(snapshot==null || snapshot.itemId()!=item.itemId()) throw new IllegalStateException("自然候选内容不可用");
                var display=new Content(snapshot.title(),snapshot.category(),snapshot.description()); int slot=entries.size()+1;
                var q=new NaturalQualification(request.operationId(),UUID.randomUUID(),request.subject(),request.userId(),item.itemId(),slot,
                        request.kind(),NaturalQualification.Traffic.SANDBOX,request.scene(),candidates.bucket(),display.hash(),operation.policy(),now,now.plusMillis(operation.policy().ttlMs()));
                entries.add(new Entry(slot,Long.toString(item.itemId()),display.title(),display.category(),display.description(),item.score(),
                        item.recallFrom(),item.reason(),q.qualificationId().toString(),tokens.sign(q),now,q.expiresAt(),q.contentHash()));
            }
            var result=new View(request.operationId().toString(),NaturalQualification.Traffic.SANDBOX,State.COMPLETE,Long.toString(request.userId()),
                    request.kind(),request.size(),request.scene(),request.query(),operation.policy(),candidates.bucket(),candidates.response().traceId(),ModelLineage.UNVERIFIED,entries);
            if(!repository.freeze(operation,result)) return find(request.operationId(),request.subject()).orElseThrow();
            return result;
        } catch(RuntimeException ex) {
            try { repository.relinquish(request.operationId(),operation.fence()); } catch(RuntimeException release) { ex.addSuppressed(release); }
            throw new IllegalStateException("自然交付未完成，请保留原操作恢复",ex);
        }
    }
    /** 只取原快照，历史详情不会重新签发或计曝光。 */
    public Optional<View> find(UUID id,String subject) { return repository.find(id,subject).map(NaturalDelivery::view); }
}
