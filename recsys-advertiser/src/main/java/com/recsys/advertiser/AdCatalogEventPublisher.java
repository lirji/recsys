package com.recsys.advertiser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.AdCatalogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 广告目录事件发布器(P1b):把广告目录快照发到 Kafka {@link AdCatalogEvent#TOPIC},供
 * {@code recsys-ad-serving} 构建自有可服务副本。
 *
 * <p><b>双写过渡 + 优雅降级</b>:与 {@code AdIndexSyncService} 同步维护 {@code bidword:inv} 并行(dual-write),
 * 由 {@code recsys.ad.catalog.publish-events} 开关(默认关 → publish 全部 no-op,无 Kafka 也能起)。
 * 以 {@code adId} 作消息 key(同一广告事件有序落同一分区);发送失败异步吞掉(best-effort,不阻断写库主流程)。
 */
@Component
public class AdCatalogEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AdCatalogEventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${recsys.ad.catalog.publish-events:false}")
    private boolean enabled;

    public AdCatalogEventPublisher(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    /** 发布广告目录快照(含墓碑)。开关关 / 序列化失败 → no-op;发送失败 → 异步记录,不抛。 */
    public void publish(AdCatalogEvent event) {
        if (!enabled || event == null) {
            return;
        }
        try {
            String json = mapper.writeValueAsString(event);
            kafka.send(AdCatalogEvent.TOPIC, String.valueOf(event.adId()), json)
                    .whenComplete((r, ex) -> {
                        if (ex != null) {
                            log.warn("发布广告目录事件失败 adId={}: {}", event.adId(), ex.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.warn("序列化/发送广告目录事件失败 adId={}: {}", event.adId(), e.getMessage());
        }
    }

    /** 广告不可服务(删除/下线)→ 发墓碑事件,消费端从可服务副本移除。 */
    public void publishTombstone(long adId) {
        publish(AdCatalogEvent.tombstone(adId, System.currentTimeMillis()));
    }
}
