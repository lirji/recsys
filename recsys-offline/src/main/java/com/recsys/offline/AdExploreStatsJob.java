package com.recsys.offline;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private final JdbcTemplate jdbc;
    private String aet = "ad_event";   // #3:ad_event 读来源表(默认 ad_event)
    private final StringRedisTemplate redis;

    public AdExploreStatsJob(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @Override
    public String name() {
        return "ad-explore-stats";
    }

    @Override
    public void run(ApplicationArguments args) {
        aet = AdEventQuery.table(args);
        AtomicLong totalImp = new AtomicLong();
        int[] ads = {0};
        jdbc.query(
                "SELECT ad_id, " +
                "  SUM(CASE WHEN event_type='IMPRESSION' THEN 1 ELSE 0 END) AS imp, " +
                "  SUM(CASE WHEN event_type='CLICK' THEN 1 ELSE 0 END) AS clk " +
                "FROM " + aet + " WHERE ad_id IS NOT NULL GROUP BY ad_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    long adId = rs.getLong("ad_id");
                    long imp = rs.getLong("imp");
                    long clk = rs.getLong("clk");
                    redis.opsForValue().set(RedisKeys.adStats(adId), imp + "," + clk);
                    totalImp.addAndGet(imp);
                    ads[0]++;
                });
        redis.opsForValue().set(RedisKeys.AD_STATS_TOTAL, String.valueOf(totalImp.get()));

        // DCO 创意级统计(docs/05 §7 M7):按 (广告, 创意) 聚合曝光/点击 → ad:cstats:{adId}:{creativeId}。
        // 曝光取 IMPRESSION 上的 creative_id;点击按 (request_id, ad_id) 关联回其曝光行取 creative_id。
        Map<String, long[]> cstats = new HashMap<>();   // "adId:creativeId" → [imp, clk]
        jdbc.query(
                "SELECT ad_id, creative_id, COUNT(*) AS imp FROM " + aet + " " +
                "WHERE event_type='IMPRESSION' AND creative_id IS NOT NULL GROUP BY ad_id, creative_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        cstats.computeIfAbsent(rs.getLong("ad_id") + ":" + rs.getLong("creative_id"),
                                k -> new long[2])[0] = rs.getLong("imp"));
        jdbc.query(
                "SELECT i.ad_id, i.creative_id, COUNT(*) AS clk FROM " + aet + " c " +
                "JOIN " + aet + " i ON i.request_id = c.request_id AND i.ad_id = c.ad_id " +
                "  AND i.event_type='IMPRESSION' AND i.creative_id IS NOT NULL " +
                "WHERE c.event_type='CLICK' GROUP BY i.ad_id, i.creative_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        cstats.computeIfAbsent(rs.getLong("ad_id") + ":" + rs.getLong("creative_id"),
                                k -> new long[2])[1] = rs.getLong("clk"));
        for (Map.Entry<String, long[]> e : cstats.entrySet()) {
            String[] p = e.getKey().split(":");
            redis.opsForValue().set(
                    RedisKeys.adCreativeStats(Long.parseLong(p[0]), Long.parseLong(p[1])),
                    e.getValue()[0] + "," + e.getValue()[1]);
        }

        log.info("ad-explore-stats 完成:写入 {} 个广告统计 + {} 个创意级统计(DCO),全局总曝光 {} → {}",
                ads[0], cstats.size(), totalImp.get(), RedisKeys.AD_STATS_TOTAL);
        if (ads[0] == 0) {
            log.warn("无 ad_event;先跑 --job=sim-ad-events 或积累真实曝光。新广告将全部得最大探索加成。");
        }
    }
}
