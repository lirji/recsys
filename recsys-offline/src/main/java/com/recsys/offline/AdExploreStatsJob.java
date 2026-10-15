package com.recsys.offline;

import com.recsys.common.error.ErrorCode;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.recsys.offline.persistence.mapper.primary.AdExplorationMapper;
import com.recsys.offline.persistence.model.AdEventSource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 作业 ad-explore-stats:从 {@code ad_event} 聚合每个广告的累计曝光/点击 → Redis
 * {@code ad:stats:{adId}}="imp,clk" 与全局 {@code ad:stats:total},供在线
 * {@link com.recsys.ad.ExplorationService} 算 UCB 探索加成(新广告 EE,docs/05 §6)。
 *
 * <p>"在线只查表、重活离线"——把曝光统计离线物化,在线探索只读 Redis。建议与 ad-calibrate 同节奏跑。
 */
@Component
public class AdExploreStatsJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(AdExploreStatsJob.class);

    private final AdExplorationMapper mapper;
    private final StringRedisTemplate redis;

    /** Mapper沿原主库会话读取，保持Redis发布的先后边界。 */
    public AdExploreStatsJob(AdExplorationMapper mapper, StringRedisTemplate redis) {
        this.mapper = mapper;
        this.redis = redis;
    }

    /** 保留既有离线命令名。 */
    @Override
    public String name() {
        return "ad-explore-stats";
    }

    /** 来源按单次运行传递，输入查询与原拟合/发布算法各自负责。 */
    @Override
    public void run(ApplicationArguments args) {
        AdEventSource source = AdEventQuery.source(args);
        AtomicLong totalImp = new AtomicLong();
        int[] ads = {0};
        mapper.scanAds(source, context -> {
            var row = context.getResultObject();
            redis.opsForValue().set(RedisKeys.adStats(row.adId()), row.impressions() + "," + row.clicks());
            totalImp.addAndGet(row.impressions());
            ads[0]++;
        });
        redis.opsForValue().set(RedisKeys.AD_STATS_TOTAL, String.valueOf(totalImp.get()));

        // DCO 创意级统计(docs/05 §7 M7):按 (广告, 创意) 聚合曝光/点击 → ad:cstats:{adId}:{creativeId}。
        // 曝光取 IMPRESSION 上的 creative_id;点击按 (request_id, ad_id) 关联回其曝光行取 creative_id。
        Map<String, long[]> cstats = new HashMap<>();   // "adId:creativeId" → [imp, clk]
        mapper.scanCreativeImpressions(source, context -> {
            var row = context.getResultObject();
            cstats.computeIfAbsent(row.adId() + ":" + row.creativeId(), k -> new long[2])[0] = row.count();
        });
        mapper.scanCreativeClicks(source, context -> {
            var row = context.getResultObject();
            cstats.computeIfAbsent(row.adId() + ":" + row.creativeId(), k -> new long[2])[1] = row.count();
        });
        for (Map.Entry<String, long[]> e : cstats.entrySet()) {
            String[] p = e.getKey().split(":");
            redis.opsForValue().set(
                    RedisKeys.adCreativeStats(Long.parseLong(p[0]), Long.parseLong(p[1])),
                    e.getValue()[0] + "," + e.getValue()[1]);
        }

        log.info("ad-explore-stats 完成:写入 {} 个广告统计 + {} 个创意级统计(DCO),全局总曝光 {} → {}",
                ads[0], cstats.size(), totalImp.get(), RedisKeys.AD_STATS_TOTAL);
        if (ads[0] == 0) {
            log.warn("{}", ErrorCode.JOB_EXPLORATION_EMPTY_SNAPSHOT.displayMessage());
        }
    }
}
