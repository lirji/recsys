package com.recsys.recengine.experiment;

import com.recsys.common.constant.ActionType;
import com.recsys.common.constant.RedisKeys;
import com.recsys.common.dto.RecommendItem;
import com.recsys.common.experiment.BucketTags;
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
 *   <li><b>点击归因</b>:写短 TTL 的 Redis 键 {@code expo:{user}:{item}=bucket},
 *       行为服务收到点击时回查,把点击也打到同一分桶维度。</li>
 * </ul>
 *
 * <p>异步 fire-and-forget(单线程 executor),不阻塞推荐主链路;失败仅告警不影响返回。
 * SQL 字段口径与 {@code BehaviorService.insert} 一致(action 存大写枚举名)。
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
        List<Long> itemIds = items.stream().map(RecommendItem::itemId).toList();
        executor.submit(() -> {
            insert(userId, scene, bucket, itemIds);
            attribute(userId, bucket, itemIds);
        });
    }

    private void insert(long userId, String scene, String bucket, List<Long> itemIds) {
        try {
            jdbc.batchUpdate(
                    "INSERT INTO user_behavior(user_id,item_id,action,value,scene,bucket,position) " +
                    "VALUES(?,?,?,?,?,?,?)",
                    new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                            ps.setLong(1, userId);
                            ps.setLong(2, itemIds.get(i));
                            ps.setString(3, ActionType.IMPRESSION.name());
                            ps.setDouble(4, i + 1); // 展示排名(沿用 value,向后兼容)
                            ps.setString(5, scene);
                            ps.setString(6, bucket);
                            ps.setInt(7, i + 1);    // 展示位次(1 基):曝光日志闭环 + PAL 用
                        }

                        @Override
                        public int getBatchSize() {
                            return itemIds.size();
                        }
                    });
        } catch (Exception e) {
            log.warn("曝光埋点落库失败 user={} bucket={}: {}", userId, bucket, e.getMessage());
        }
    }

    /** 写点击归因键:expo:{user}:{item}=bucket(短 TTL)。Redis 不可用时静默,不影响埋点。 */
    private void attribute(long userId, String bucket, List<Long> itemIds) {
        try {
            for (Long itemId : itemIds) {
                redis.opsForValue().set(
                        RedisKeys.exposureBucket(userId, itemId), bucket, ATTRIBUTION_TTL);
            }
        } catch (Exception e) {
            log.debug("写曝光归因键失败(忽略) user={}: {}", userId, e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
