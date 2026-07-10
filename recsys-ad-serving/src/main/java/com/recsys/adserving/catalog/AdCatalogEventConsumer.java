package com.recsys.adserving.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.AdCatalogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 广告目录事件消费者(P1b):消费广告主发布的 {@link AdCatalogEvent} 快照,幂等维护可服务副本 {@code ad_servable}。
 *
 * <p>快照式 + 以 adId 分区有序 ⇒ last-write-wins 即正确,无需版本比对。{@code servable=true} → upsert,
 * {@code false}(墓碑/下线)→ delete。监听器默认不启动({@code recsys.ad.catalog.consume=false} → autoStartup=false),
 * 故无 Kafka / 不开启时零连接、服务照常起。反序列化失败吞掉(坏消息不阻断消费,交幂等重放/告警)。
 */
@Component
public class AdCatalogEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AdCatalogEventConsumer.class);

    private final AdServableRepository repo;
    private final BidwordInvMaintainer bidwordInv;
    private final ObjectMapper mapper = new ObjectMapper();

    public AdCatalogEventConsumer(AdServableRepository repo, BidwordInvMaintainer bidwordInv) {
        this.repo = repo;
        this.bidwordInv = bidwordInv;
    }

    @KafkaListener(
            topics = AdCatalogEvent.TOPIC,
            groupId = "${spring.kafka.consumer.group-id:recsys-ad-serving-catalog}",
            autoStartup = "${recsys.ad.catalog.consume:false}")
    public void onEvent(String json) {
        try {
            AdCatalogEvent e = mapper.readValue(json, AdCatalogEvent.class);
            // 先取应用前的旧竞价词(算关键词 diff);再应用事件;最后维护倒排(ad-serving 侧拥有 bidword:inv)。
            List<AdCatalogEvent.Bidword> oldBidwords = repo.bidwordsOf(e.adId());
            if (e.servable()) {
                repo.upsert(e);
                repo.upsertEmbedding(e.adId(), e.embedding());   // #3:事件带向量 → 写自有 ad_embedding
            } else {
                repo.delete(e.adId());
                repo.deleteEmbedding(e.adId());
            }
            bidwordInv.apply(e, oldBidwords);
            log.debug("消费广告目录事件 adId={} servable={}", e.adId(), e.servable());
        } catch (Exception ex) {
            log.warn("解析/应用广告目录事件失败: {} | payload={}", ex.getMessage(),
                    json != null && json.length() > 200 ? json.substring(0, 200) : json);
        }
    }
}
