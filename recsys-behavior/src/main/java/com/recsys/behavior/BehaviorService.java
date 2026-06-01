package com.recsys.behavior;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.constant.ActionType;
import com.recsys.common.constant.RedisKeys;
import com.recsys.common.dto.BehaviorEvent;
import com.recsys.common.experiment.BucketTags;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.EnumSet;
import java.util.Set;

/**
 * 行为落库/投递服务(Track E · E1)。
 *
 * <p>反馈闭环的数据入口:在线埋点 → user_behavior 表(离线 ItemCF/热度/用户向量的输入)。
 *
 * <p>两种落地方式由开关 {@code recsys.behavior.use-kafka} 控制:
 * <ul>
 *   <li>false(默认,v1):直接 INSERT user_behavior,不依赖 Kafka,本地最省心;</li>
 *   <li>true:投递 Kafka(后续由消费者落库/进流式特征)。Kafka 未就绪时自动降级入库,不丢数据。</li>
 * </ul>
 *
 * <p>action 一律以大写({@link ActionType#name()})入库,与 I2iRecaller 的
 * {@code action IN ('CLICK','LIKE','PLAY','RATING')} 查询口径对齐。
 */
@Service
public class BehaviorService {

    private static final Logger log = LoggerFactory.getLogger(BehaviorService.class);

    /** 计入点击侧 CTR 分子的正反馈行为(与曝光 IMPRESSION 相对)。 */
    private static final Set<ActionType> CLICK_ACTIONS =
            EnumSet.of(ActionType.CLICK, ActionType.LIKE, ActionType.PLAY);

    private final JdbcTemplate jdbc;
    private final ObjectProvider<KafkaTemplate<String, String>> kafkaProvider;
    private final StringRedisTemplate redis;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${recsys.behavior.use-kafka:false}")
    private boolean useKafka;

    @Value("${recsys.behavior.topic:behavior-events}")
    private String topic;

    public BehaviorService(JdbcTemplate jdbc,
                           ObjectProvider<KafkaTemplate<String, String>> kafkaProvider,
                           StringRedisTemplate redis,
                           MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.kafkaProvider = kafkaProvider;
        this.redis = redis;
        this.meterRegistry = meterRegistry;
    }

    /** 接收一条行为事件:服务端补全时间戳后落地。 */
    public void record(BehaviorEvent raw) {
        BehaviorEvent ev = normalize(raw);
        recordClickMetric(ev);
        if (useKafka) {
            KafkaTemplate<String, String> kafka = kafkaProvider.getIfAvailable();
            if (kafka != null) {
                sendKafka(kafka, ev);
                return;
            }
            log.warn("use-kafka=true 但 KafkaTemplate 不可用,降级直接入库");
        }
        insert(ev);
    }

    /**
     * 在线分桶 CTR 的分子:点击类行为按其曝光分桶打 {@code recsys.click} 计数。
     * 分桶来源优先用事件自带 bucket(客户端回传),否则回查编排层写的归因键
     * {@code expo:{user}:{item}}。Grafana 里 {@code recsys.click / recsys.exposure} 即分桶 CTR。
     *
     * <p>不阻塞、不抛错:Redis 不可用或无归因记录时,bucket 退化为 na 仍计数,只是归因缺失。
     */
    private void recordClickMetric(BehaviorEvent ev) {
        if (ev.action() == null || !CLICK_ACTIONS.contains(ev.action())) {
            return;
        }
        String bucket = ev.bucket();
        if (bucket == null || bucket.isBlank()) {
            try {
                bucket = redis.opsForValue().get(RedisKeys.exposureBucket(ev.userId(), ev.itemId()));
            } catch (Exception e) {
                log.debug("回查曝光归因失败(忽略) user={} item={}: {}", ev.userId(), ev.itemId(), e.getMessage());
            }
        }
        BucketTags tags = BucketTags.parse(bucket);
        meterRegistry.counter("recsys.click",
                "action", ev.action().name(),
                "recall", tags.recall(), "rank", tags.rank(),
                "rerank", tags.rerank(), "cold", String.valueOf(tags.cold()))
                .increment();
    }

    private BehaviorEvent normalize(BehaviorEvent ev) {
        long ts = ev.ts() > 0 ? ev.ts() : System.currentTimeMillis();
        return new BehaviorEvent(ev.userId(), ev.itemId(), ev.action(),
                ev.value(), ev.scene(), ev.bucket(), ts);
    }

    private void insert(BehaviorEvent ev) {
        // action 存大写枚举名,与召回侧查询口径一致
        jdbc.update(
                "INSERT INTO user_behavior(user_id,item_id,action,value,scene,bucket,ts) " +
                "VALUES(?,?,?,?,?,?,?)",
                ev.userId(), ev.itemId(),
                ev.action() == null ? null : ev.action().name(),
                ev.value(), ev.scene(), ev.bucket(), new Timestamp(ev.ts()));
    }

    private void sendKafka(KafkaTemplate<String, String> kafka, BehaviorEvent ev) {
        try {
            // 按 userId 分区,保证同一用户行为有序
            kafka.send(topic, String.valueOf(ev.userId()), mapper.writeValueAsString(ev))
                    .whenComplete((r, e) -> {
                        if (e != null) {
                            log.warn("Kafka 投递失败,降级入库: {}", e.getMessage());
                            insert(ev);
                        }
                    });
        } catch (Exception e) {
            log.warn("序列化/投递异常,降级入库: {}", e.getMessage());
            insert(ev);
        }
    }
}
