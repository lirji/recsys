package com.recsys.recengine.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.constant.RedisKeys;
import com.recsys.common.dto.BehaviorEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 已看读模型消费者(P4):消费 {@code behavior-events},把用户<b>正反馈</b>(CLICK/LIKE/PLAY/RATING)物品
 * SADD 进 rec-engine 自有的 {@code seen:{user}} Set —— 供 {@link ReplicaSeenItemsSource} 读,在线不再直读
 * {@code user_behavior}(DB-per-service)。
 *
 * <p>监听器默认不启动({@code recsys.behavior.seen-consume=false} → autoStartup=false),故无 Kafka / 不开启时零连接、
 * 服务照常起。以 userId 分区有序 + Set 幂等 ⇒ 无需去重。IMPRESSION 等非正反馈忽略(与 db 口径一致)。
 */
@Component
public class SeenItemsConsumer {

    private static final Logger log = LoggerFactory.getLogger(SeenItemsConsumer.class);

    /** 与 DbSeenItemsSource 的 {@code action IN (...)} 口径一致。 */
    private static final Set<String> POSITIVE = Set.of("CLICK", "LIKE", "PLAY", "RATING");

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    public SeenItemsConsumer(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @KafkaListener(
            topics = "${recsys.behavior.topic:behavior-events}",
            groupId = "${recsys.behavior.seen-group:recsys-rec-engine-seen}",
            autoStartup = "${recsys.behavior.seen-consume:false}")
    public void onEvent(String json) {
        try {
            BehaviorEvent ev = mapper.readValue(json, BehaviorEvent.class);
            if (ev.action() != null && POSITIVE.contains(ev.action().name())) {
                redis.opsForSet().add(RedisKeys.seenItems(ev.userId()), String.valueOf(ev.itemId()));
            }
        } catch (Exception e) {
            log.warn("消费 behavior-events 建已看读模型失败: {}", e.getMessage());
        }
    }
}
