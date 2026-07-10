package com.recsys.ad;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 默认目录读:直读广告主分片目录库(ShardingSphere 次数据源 {@code adShardingJdbc})。
 * 与 P1b 之前完全等价,是绞杀者迁移的安全默认与回滚落点
 * ({@code recsys.ad.catalog.source} 缺省或 = {@code sharded} 时生效)。
 *
 * <p>本类<b>拥有全部广告目录 SQL</b>(自 {@link AdRepository} 抽出),不依赖 {@code AdRepository} —— 从而
 * {@code AdRepository} 反过来可注入本接口做召回回填(activeAdDetails/bidMap)而无环。口径:只取
 * 广告 active + 审核 approved + 广告主非 paused。
 */
@Component
@ConditionalOnProperty(name = "recsys.ad.catalog.source", havingValue = "sharded", matchIfMissing = true)
public class ShardedAdCatalogReader implements AdCatalogReader {

    private static final Logger log = LoggerFactory.getLogger(ShardedAdCatalogReader.class);

    private final JdbcTemplate sharded;

    public ShardedAdCatalogReader(@Qualifier("adShardingJdbc") JdbcTemplate sharded) {
        this.sharded = sharded;
    }

    @Override
    public Map<Long, String> titles(Collection<Long> adIds) {
        Map<Long, String> out = new HashMap<>();
        if (adIds.isEmpty()) {
            return out;
        }
        Long[] ids = adIds.toArray(new Long[0]);
        try {
            sharded.query("SELECT ad_id, title FROM ad WHERE ad_id = ANY(?)",
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids)),
                    rs -> { out.put(rs.getLong("ad_id"), rs.getString("title")); });
        } catch (Exception e) {
            log.warn("取广告标题失败: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public Map<Long, AdRepository.OcpcParams> ocpcParams(Collection<Long> adIds) {
        Map<Long, AdRepository.OcpcParams> out = new HashMap<>();
        if (adIds.isEmpty()) {
            return out;
        }
        Long[] ids = adIds.toArray(new Long[0]);
        try {
            sharded.query("SELECT ad_id, optimization_type, target_cpa FROM ad WHERE ad_id = ANY(?)",
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids)),
                    rs -> {
                        String type = rs.getString("optimization_type");
                        double cpa = rs.getDouble("target_cpa");
                        out.put(rs.getLong("ad_id"),
                                new AdRepository.OcpcParams(type == null ? "CPC" : type, rs.wasNull() ? 0.0 : cpa));
                    });
        } catch (Exception e) {
            log.warn("取广告 oCPC 参数失败,退 CPC: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public Map<Long, AdDetail> activeAdDetails(Collection<Long> adIds) {
        Map<Long, AdDetail> out = new HashMap<>();
        // A2:只取 审核 approved 的广告 —— 与倒排"可服务集"口径一致。
        String base =
                "SELECT a.ad_id, a.item_id, a.advertiser_id, a.quality_score " +
                "FROM ad a JOIN advertiser adv ON adv.advertiser_id = a.advertiser_id AND adv.status <> 'paused' " +
                "WHERE a.status = 'active' AND a.review_status = 'approved'";
        try {
            if (adIds == null) {
                sharded.query(base, rs -> { putDetail(out, rs); });
            } else {
                if (adIds.isEmpty()) {
                    return out;
                }
                Long[] ids = adIds.toArray(new Long[0]);
                sharded.query(base + " AND a.ad_id = ANY(?)",
                        ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids)),
                        rs -> { putDetail(out, rs); });
            }
        } catch (Exception e) {
            log.warn("取广告详情失败: {}", e.getMessage());
        }
        return out;
    }

    private static void putDetail(Map<Long, AdDetail> out, java.sql.ResultSet rs) throws java.sql.SQLException {
        out.put(rs.getLong("ad_id"), new AdDetail(
                rs.getLong("item_id"), rs.getLong("advertiser_id"), rs.getDouble("quality_score")));
    }

    @Override
    public Map<Long, Double> bidMap(Collection<Long> adIds) {
        Map<Long, Double> out = new HashMap<>();
        try {
            if (adIds == null) {
                sharded.query("SELECT ad_id, COALESCE(MAX(bid),0) AS bid FROM bidword GROUP BY ad_id",
                        rs -> { out.put(rs.getLong("ad_id"), rs.getDouble("bid")); });
            } else {
                if (adIds.isEmpty()) {
                    return out;
                }
                Long[] ids = adIds.toArray(new Long[0]);
                sharded.query(
                        "SELECT ad_id, COALESCE(MAX(bid),0) AS bid FROM bidword WHERE ad_id = ANY(?) GROUP BY ad_id",
                        ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids)),
                        rs -> { out.put(rs.getLong("ad_id"), rs.getDouble("bid")); });
            }
        } catch (Exception e) {
            log.warn("取广告出价失败: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public Map<Long, Long> audiencesByAd(Collection<Long> adIds) {
        Map<Long, Long> out = new HashMap<>();
        if (adIds == null || adIds.isEmpty()) {
            return out;
        }
        Long[] ids = adIds.toArray(new Long[0]);
        try {
            sharded.query(
                    "SELECT ad_id, audience_id FROM ad WHERE audience_id IS NOT NULL AND ad_id = ANY(?)",
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids)),
                    rs -> { out.put(rs.getLong("ad_id"), rs.getLong("audience_id")); });
        } catch (Exception e) {
            log.warn("取广告定向人群失败(退不定向): {}", e.getMessage());
        }
        return out;
    }
}
