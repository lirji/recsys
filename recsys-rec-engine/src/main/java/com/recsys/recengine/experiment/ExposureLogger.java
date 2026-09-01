package com.recsys.recengine.experiment;

import com.recsys.common.constant.ActionType;
import com.recsys.common.constant.RedisKeys;
import com.recsys.common.dto.RecommendItem;
import com.recsys.common.experiment.BucketTags;
import com.recsys.common.experiment.ExposureAttribution;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 曝光埋点:把每次推荐返回的物品作为 {@link ActionType#IMPRESSION} 行为落 {@code user_behavior},
 * 关键是带上分层实验的 {@code bucket} 标签,后续可 {@code GROUP BY bucket} 算分桶 CTR
 * (离线 {@code AbReportJob} 做 T+1 精算)。
 *
 * <p>本类同时承担在线观测性的曝光侧:
 * <ul>
 *   <li><b>实时指标</b>:按分桶给 {@code recsys.exposure}(单位=曝光物品数)计数,
 *       供 Grafana 与 {@code recsys.click} 相除得在线分桶 CTR;</li>
 *   <li><b>点击归因</b>:按 {@code exposureId} 写精确归因，同时双写最近曝光与旧 bucket 键支持滚动升级。</li>
 * </ul>
 *
 * <p>Redis 精确归因在返回前同步完成，消除“用户快速点击早于异步归因”的竞态；DB 曝光日志仍由
 * 单线程 executor 异步落地，失败仅告警不影响推荐返回。
 */
@Component
public class ExposureLogger {

    private static final Logger log = LoggerFactory.getLogger(ExposureLogger.class);

    /** 归因键 TTL:覆盖"曝光后到点击"的常规窗口即可,避免 Redis 长期堆积。 */
    private static final Duration ATTRIBUTION_TTL = Duration.ofHours(2);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final MeterRegistry meterRegistry;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "exposure-logger");
                t.setDaemon(true);
                return t;
            });

    public ExposureLogger(JdbcTemplate jdbc, StringRedisTemplate redis, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.meterRegistry = meterRegistry;
    }

    /** 异步记录一次曝光:每个推荐物品一行,value=展示排名(1 基)。 */
    public void log(long userId, String scene, String bucket, List<RecommendItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        // 同步打点(计数器线程安全,主链路开销可忽略),避免与异步任务竞争
        BucketTags tags = BucketTags.parse(bucket);
        meterRegistry.counter("recsys.exposure",
                "recall", tags.recall(), "rank", tags.rank(),
                "rerank", tags.rerank(), "cold", String.valueOf(tags.cold()))
                .increment(items.size());

        // 拷贝出落库所需的最小数据,避免异步任务持有上层对象
        List<Delivery> deliveries = items.stream()
                .map(i -> new Delivery(i.itemId(), i.exposureId()))
                .toList();
        // 必须在 log 返回前建立精确归因；否则推荐响应刚到客户端就点击，会永久落成 bucket=null。
        attribute(userId, bucket, deliveries);
        executor.submit(() -> insert(userId, scene, bucket, deliveries));
    }

    private void insert(long userId, String scene, String bucket, List<Delivery> deliveries) {
        try {
            jdbc.batchUpdate(
                    "INSERT INTO user_behavior(user_id,item_id,action,value,scene,bucket,position,exposure_id) " +
                    "VALUES(?,?,?,?,?,?,?,?)",
                    new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                            Delivery delivery = deliveries.get(i);
                            ps.setLong(1, userId);
                            ps.setLong(2, delivery.itemId());
                            ps.setString(3, ActionType.IMPRESSION.name());
                            ps.setDouble(4, i + 1); // 展示排名(沿用 value,向后兼容)
                            ps.setString(5, scene);
                            ps.setString(6, bucket);
                            ps.setInt(7, i + 1);    // 展示位次(1 基):曝光日志闭环 + PAL 用
                            ps.setString(8, delivery.exposureId());
                        }

                        @Override
                        public int getBatchSize() {
                            return deliveries.size();
                        }
                    });
        } catch (Exception e) {
            log.warn("曝光埋点落库失败 user={} bucket={}: {}", userId, bucket, e.getMessage());
        }
    }

    /** 写精确曝光、最近曝光与旧 bucket 三组短 TTL 键。Redis 不可用时静默，不影响推荐。 */
    private void attribute(long userId, String bucket, List<Delivery> deliveries) {
        try {
            for (Delivery delivery : deliveries) {
                long itemId = delivery.itemId();
                String exposureId = delivery.exposureId();
                // 旧服务仍按 expo:{user}:{item} 读 bucket，迁移期继续维护。
                redis.opsForValue().set(
                        RedisKeys.exposureBucket(userId, itemId), bucket == null ? "" : bucket, ATTRIBUTION_TTL);
                if (exposureId == null || exposureId.isBlank()) {
                    continue;
                }
                redis.opsForValue().set(RedisKeys.latestExposure(userId, itemId), exposureId, ATTRIBUTION_TTL);
                redis.opsForValue().set(RedisKeys.exposure(exposureId),
                        new ExposureAttribution(userId, itemId, bucket).encode(), ATTRIBUTION_TTL);
            }
        } catch (Exception e) {
            log.debug("写曝光归因键失败(忽略) user={}: {}", userId, e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    private record Delivery(long itemId, String exposureId) {
    }
}
