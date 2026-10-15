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
import com.recsys.recengine.persistence.mapper.primary.EngineBehaviorMapper;
import com.recsys.recengine.persistence.model.ExposureWrite;
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

    private final EngineBehaviorMapper mapper;
    private final StringRedisTemplate redis;
    private final MeterRegistry meterRegistry;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "exposure-logger");
                t.setDaemon(true);
                return t;
            });

    /** 保持原异步生命周期和同步Redis归因，Mapper只替换SQL执行边界。 */
    public ExposureLogger(EngineBehaviorMapper mapper, StringRedisTemplate redis, MeterRegistry meterRegistry) {
        this.mapper = mapper;
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
        List<ExposureWrite> deliveries = java.util.stream.IntStream.range(0, items.size())
                .mapToObj(i -> new ExposureWrite(items.get(i).itemId(), items.get(i).exposureId(), i + 1))
                .toList();
        // 必须在 log 返回前建立精确归因；否则推荐响应刚到客户端就点击，会永久落成 bucket=null。
        attribute(userId, bucket, deliveries);
        executor.submit(() -> insert(userId, scene, bucket, deliveries));
    }

    private void insert(long userId, String scene, String bucket, List<ExposureWrite> deliveries) {
        try {
            // 多VALUES语句保留旧PG批次的整批失败语义，value和position仍使用一基排名。
            mapper.appendExposures(userId, scene, bucket, ActionType.IMPRESSION.name(), deliveries);
        } catch (Exception e) {
            log.warn("曝光埋点落库失败 user={} bucket={}: {}", userId, bucket, e.getMessage());
        }
    }

    /** 写精确曝光、最近曝光与旧 bucket 三组短 TTL 键。Redis 不可用时静默，不影响推荐。 */
    private void attribute(long userId, String bucket, List<ExposureWrite> deliveries) {
        try {
            for (ExposureWrite delivery : deliveries) {
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
}
