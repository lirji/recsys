package com.recsys.ad;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DCO 创意选择在线服务(docs/05 §7 M7):对竞得展示的广告,用多臂老虎机 {@link CreativeBandit} 从其
 * 多套创意里选一条展示。创意级统计 {@code ad:cstats:{adId}:{creativeId}} 由离线 {@code ad-explore-stats}
 * 物化,本服务在线只查表 + 跑 UCB(在线只查 + 轻算,重活离线)。
 *
 * <p><b>优雅降级</b>:开关关 / 广告无创意 / Redis 不可用 → 该广告不在返回 map 里,编排层保留广告默认创意。
 * 异常整体吞掉返回空 map(不影响出广告)。
 *
 * <p>计费/排序完全不受影响——DCO 只决定"赢得展示后给用户看哪套创意",在竞价之后(见
 * {@code SearchAdsOrchestrator})。展示创意经 {@code ad_event.creative_id} 归因,闭环喂回 ad-explore-stats。
 */
@Service
public class CreativeSelector {

    private static final Logger log = LoggerFactory.getLogger(CreativeSelector.class);

    /** 分片库:ad_creative 按 ad_id 分布在 ds_0/ds_1(ad_id IN 查询路由/广播)。 */
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final AdProperties props;

    public CreativeSelector(@Qualifier("adShardingJdbc") JdbcTemplate jdbc,
                            StringRedisTemplate redis, AdProperties props) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.props = props;
    }

    /** 选中的创意(展示用 + 归因用)。 */
    public record Choice(long creativeId, String title) {
    }

    /**
     * 为一批广告各选一条创意。返回 {@code adId → Choice};无创意/异常的广告不在 map 里(调用方保留默认)。
     */
    public Map<Long, Choice> selectFor(Collection<Long> adIds) {
        Map<Long, Choice> out = new HashMap<>();
        if (!props.getDco().isEnabled() || adIds == null || adIds.isEmpty()) {
            return out;
        }
        try {
            Map<Long, List<long[]>> creativesByAd = loadCreatives(adIds);   // adId → [(creativeId)]
            Map<Long, String> titleById = new HashMap<>();                   // creativeId → title
            loadCreativeTitles(adIds, creativesByAd, titleById);
            double coef = props.getDco().getUcbCoef();
            for (Map.Entry<Long, List<long[]>> e : creativesByAd.entrySet()) {
                long adId = e.getKey();
                List<CreativeBandit.Arm> arms = new ArrayList<>(e.getValue().size());
                for (long[] c : e.getValue()) {
                    long cid = c[0];
                    long[] s = readStats(adId, cid);
                    arms.add(new CreativeBandit.Arm(cid, s[0], s[1]));
                }
                Long chosen = CreativeBandit.select(arms, coef);
                if (chosen != null && titleById.containsKey(chosen)) {
                    out.put(adId, new Choice(chosen, titleById.get(chosen)));
                }
            }
        } catch (Exception ex) {
            log.debug("DCO 创意选择失败,退默认创意: {}", ex.getMessage());
            return new HashMap<>();
        }
        return out;
    }

    /** 批量加载各广告的活跃创意 id(保持稳定顺序,空臂时 UCB 取首条)。 */
    private Map<Long, List<long[]>> loadCreatives(Collection<Long> adIds) {
        Map<Long, List<long[]>> byAd = new LinkedHashMap<>();
        String ph = String.join(",", adIds.stream().map(x -> "?").toList());
        jdbc.query(
                "SELECT ad_id, creative_id FROM ad_creative WHERE status='active' AND review_status='approved' AND ad_id IN (" + ph + ") " +
                "ORDER BY ad_id, creative_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        byAd.computeIfAbsent(rs.getLong("ad_id"), k -> new ArrayList<>())
                                .add(new long[]{rs.getLong("creative_id")}),
                adIds.toArray());
        return byAd;
    }

    private void loadCreativeTitles(Collection<Long> adIds, Map<Long, List<long[]>> creativesByAd,
                                    Map<Long, String> titleById) {
        if (creativesByAd.isEmpty()) {
            return;
        }
        String ph = String.join(",", adIds.stream().map(x -> "?").toList());
        jdbc.query(
                "SELECT creative_id, title FROM ad_creative WHERE status='active' AND review_status='approved' AND ad_id IN (" + ph + ")",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        titleById.put(rs.getLong("creative_id"), rs.getString("title")),
                adIds.toArray());
    }

    /** 读创意级统计 ad:cstats:{adId}:{creativeId}="imp,clk";缺失/异常 → {0,0}(新创意,UCB 强制探索)。 */
    private long[] readStats(long adId, long creativeId) {
        try {
            String s = redis.opsForValue().get(RedisKeys.adCreativeStats(adId, creativeId));
            if (s != null && !s.isBlank()) {
                int comma = s.indexOf(',');
                if (comma > 0) {
                    return new long[]{parse(s.substring(0, comma)), parse(s.substring(comma + 1))};
                }
            }
        } catch (Exception ignored) {
            // 缺失/异常 → 新创意
        }
        return new long[]{0, 0};
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
