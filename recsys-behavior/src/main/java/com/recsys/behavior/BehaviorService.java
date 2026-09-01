package com.recsys.behavior;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.constant.ActionType;
import com.recsys.common.constant.RedisKeys;
import com.recsys.common.dto.BehaviorEvent;
import com.recsys.common.experiment.BucketTags;
import com.recsys.common.experiment.ExposureAttribution;
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
 * <p>PostgreSQL 是行为写权威；开关 {@code recsys.behavior.use-kafka} 控制是否在首次入库后再投递事件:
 * <ul>
 *   <li>false(默认):直接 INSERT user_behavior；{@code publish-events=true} 时额外发布读模型事件；</li>
 *   <li>true:先幂等 INSERT，再 best-effort 投递 Kafka。DB 成功、Kafka 失败时不重复回插。</li>
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

    /** P4:use-kafka=false 时，是否在权威入库之外额外把事件发到 behavior-events。 */
    @Value("${recsys.behavior.publish-events:false}")
    private boolean publishEvents;

    public BehaviorService(JdbcTemplate jdbc,
                           ObjectProvider<KafkaTemplate<String, String>> kafkaProvider,
                           StringRedisTemplate redis,
                           MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.kafkaProvider = kafkaProvider;
        this.redis = redis;
        this.meterRegistry = meterRegistry;
    }

    /** 接收一条行为事件:点击类行为使用服务端接收时间，并用服务端曝光归因后落地。 */
    public void record(BehaviorEvent raw) {
        BehaviorEvent ev = resolveExposureBucket(normalize(raw));
        // DB 是 CLICK 幂等权威；重复曝光 ID updateCount=0，HTTP 仍返回 200，但不重复计数/发事件。
        if (!insert(ev)) {
            log.debug("重复行为已幂等忽略 action={} exposure={}", ev.action(), ev.exposureId());
            return;
        }
        recordClickMetric(ev);
        if (useKafka) {
            KafkaTemplate<String, String> kafka = kafkaProvider.getIfAvailable();
            if (kafka != null) {
                sendKafka(kafka, ev);
                return;
            }
            log.warn("use-kafka=true 但 KafkaTemplate 不可用;事件已入 DB,跳过 Kafka 发布");
        }
        // P4:DB 是 user_behavior 写权威;开 publish-events 时额外把事件发到 behavior-events,
        // 供在线(rec-engine 已看读模型)/离线消费者建各自读模型(双写过渡)。
        if (publishEvents) {
            publishEvent(ev);
        }
    }

    /** 仅发布事件(已入库,失败不再回插;best-effort)。 */
    private void publishEvent(BehaviorEvent ev) {
        KafkaTemplate<String, String> kafka = kafkaProvider.getIfAvailable();
        if (kafka == null) {
            return;
        }
        try {
            kafka.send(topic, String.valueOf(ev.userId()), mapper.writeValueAsString(ev))
                    .whenComplete((r, e) -> {
                        if (e != null) {
                            log.warn("发布 behavior-events 失败 user={}: {}", ev.userId(), e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.warn("序列化/发布 behavior-events 失败: {}", e.getMessage());
        }
    }

    /**
     * 在线分桶 CTR 的分子:点击类行为按其曝光分桶打 {@code recsys.click} 计数。
     * 分桶已由 {@link #resolveExposureBucket} 统一补全。Grafana 里
     * {@code recsys.click / recsys.exposure} 即分桶 CTR。
     *
     * <p>bucket 缺失时退化为 na 仍计数,只是归因缺失。
     */
    private void recordClickMetric(BehaviorEvent ev) {
        if (ev.action() == null || !CLICK_ACTIONS.contains(ev.action())) {
            return;
        }
        BucketTags tags = BucketTags.parse(ev.bucket());
        meterRegistry.counter("recsys.click",
                "action", ev.action().name(),
                "recall", tags.recall(), "rank", tags.rank(),
                "rerank", tags.rerank(), "cold", String.valueOf(tags.cold()))
                .increment();
    }

    /**
     * 点击类事件一律回查服务端曝光归因键并把结果写回事件本身。优先按客户端带回的 exposureId
     * 精确校验 user/item；旧客户端缺 ID 时按最近曝光补齐；迁移期最后回退旧 bucket-only 键。
     * 这样 Micrometer、DB、Kafka
     * 三个消费者看到的是同一个 bucket；此前只给指标临时补 bucket、落库仍为 null，会让 ab-report/CUPED
     * 的点击分子无法归因。客户端 bucket 不作为实验臂真值；Redis 不可用或键过期时再校验权威
     * IMPRESSION 行，仍无法验证则清空 exposureId/bucket，不允许伪造归因且不阻塞行为写入。
     */
    private BehaviorEvent resolveExposureBucket(BehaviorEvent ev) {
        if (ev.action() == null || !CLICK_ACTIONS.contains(ev.action())) {
            return ev;
        }
        String bucket = null;
        String exposureId = normalizeExposureId(ev.exposureId());
        boolean exactMismatch = false;
        boolean exactVerified = false;
        try {
            if (exposureId != null) {
                String encoded = redis.opsForValue().get(RedisKeys.exposure(exposureId));
                var attribution = ExposureAttribution.decode(encoded);
                if (attribution.isPresent()) {
                    if (attribution.get().matches(ev.userId(), ev.itemId())) {
                        bucket = attribution.get().bucket();
                        exactVerified = true;
                    } else {
                        // 明确命中但 user/item 不匹配：拒绝伪造/串用这个 ID。
                        exactMismatch = true;
                        exposureId = null;
                    }
                }
            } else {
                String latest = redis.opsForValue().get(RedisKeys.latestExposure(ev.userId(), ev.itemId()));
                String encoded = latest == null ? null : redis.opsForValue().get(RedisKeys.exposure(latest));
                var attribution = ExposureAttribution.decode(encoded);
                if (attribution.isPresent() && attribution.get().matches(ev.userId(), ev.itemId())) {
                    exposureId = latest;
                    bucket = attribution.get().bucket();
                } else {
                    // 滚动升级兼容：老 ExposureLogger 只有 expo:{user}:{item}=bucket。
                    bucket = redis.opsForValue().get(RedisKeys.exposureBucket(ev.userId(), ev.itemId()));
                }
            }
        } catch (Exception e) {
            log.debug("回查曝光归因失败(忽略) user={} item={}: {}", ev.userId(), ev.itemId(), e.getMessage());
        }
        // 客户端 ID 只有经 Redis 或权威 IMPRESSION 行校验后才能进入全局唯一键；固定伪造 ID 不能误伤他人点击。
        if (ev.exposureId() != null && !exactVerified && !exactMismatch) {
            var dbAttribution = findDbAttribution(normalizeExposureId(ev.exposureId()), ev.userId(), ev.itemId());
            if (dbAttribution.isPresent()) {
                exposureId = normalizeExposureId(ev.exposureId());
                bucket = dbAttribution.get().bucket();
            } else {
                exposureId = null;
                bucket = null;
            }
        }
        if (bucket != null && bucket.isBlank()) {
            bucket = null;
        }
        return new BehaviorEvent(ev.userId(), ev.itemId(), ev.action(),
                ev.value(), ev.scene(), bucket, ev.ts(), exposureId);
    }

    private BehaviorEvent normalize(BehaviorEvent ev) {
        // 实验 pre/post 边界必须依赖服务端可信时间；非点击类事件仍保留历史上报时间语义。
        long ts = ev.action() != null && CLICK_ACTIONS.contains(ev.action())
                ? System.currentTimeMillis()
                : (ev.ts() > 0 ? ev.ts() : System.currentTimeMillis());
        return new BehaviorEvent(ev.userId(), ev.itemId(), ev.action(),
                ev.value(), ev.scene(), ev.bucket(), ts, normalizeExposureId(ev.exposureId()));
    }

    private boolean insert(BehaviorEvent ev) {
        // action 存大写枚举名,与召回侧查询口径一致
        int updated = jdbc.update(
                "INSERT INTO user_behavior(user_id,item_id,action,value,scene,bucket,exposure_id,ts) " +
                "VALUES(?,?,?,?,?,?,?,?) " +
                "ON CONFLICT (exposure_id) WHERE action='CLICK' AND exposure_id IS NOT NULL DO NOTHING",
                ev.userId(), ev.itemId(),
                ev.action() == null ? null : ev.action().name(),
                ev.value(), ev.scene(), ev.bucket(), ev.exposureId(), new Timestamp(ev.ts()));
        return updated > 0;
    }

    private void sendKafka(KafkaTemplate<String, String> kafka, BehaviorEvent ev) {
        try {
            // 按 userId 分区,保证同一用户行为有序
            kafka.send(topic, String.valueOf(ev.userId()), mapper.writeValueAsString(ev))
                    .whenComplete((r, e) -> {
                        if (e != null) {
                            log.warn("Kafka 投递失败(事件已在 DB): {}", e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.warn("序列化/投递异常(事件已在 DB): {}", e.getMessage());
        }
    }

    private static String normalizeExposureId(String exposureId) {
        if (exposureId == null) {
            return null;
        }
        String normalized = exposureId.trim();
        return normalized.isEmpty() || normalized.length() > 128 ? null : normalized;
    }

    private java.util.Optional<ExposureAttribution> findDbAttribution(
            String exposureId, long userId, long itemId) {
        if (exposureId == null) {
            return java.util.Optional.empty();
        }
        try {
            java.util.Optional<ExposureAttribution> result = jdbc.query("""
                            SELECT bucket FROM user_behavior
                            WHERE exposure_id=? AND user_id=? AND item_id=? AND action='IMPRESSION'
                            ORDER BY id DESC LIMIT 1
                            """,
                    ps -> {
                        ps.setString(1, exposureId);
                        ps.setLong(2, userId);
                        ps.setLong(3, itemId);
                    },
                    rs -> rs.next()
                            ? java.util.Optional.of(new ExposureAttribution(userId, itemId, rs.getString("bucket")))
                            : java.util.Optional.empty());
            return result == null ? java.util.Optional.empty() : result;
        } catch (Exception e) {
            log.debug("DB 校验曝光失败(按未验证处理) exposure={}: {}", exposureId, e.getMessage());
            return java.util.Optional.empty();
        }
    }
}
