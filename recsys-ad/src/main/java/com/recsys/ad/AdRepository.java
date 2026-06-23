package com.recsys.ad;

import com.pgvector.PGvector;
import com.recsys.common.ad.AdCandidate;
import com.recsys.common.ad.AdChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 广告库读取(JDBC)。ad / bidword / advertiser / ad_embedding 的真源访问。
 *
 * <p><b>分库分表(docs/05 §8)</b>:广告表(advertiser/ad/bidword/ad_creative)分布在 ds_0/ds_1,经
 * {@code adShardingJdbc}(ShardingSphere)读;而 {@code ad_embedding} 不分片(向量 ANN 需全量),其 pgvector
 * {@code <=>} 查询走主数据源 {@code jdbc} 直查。因此凡"向量 ANN + 广告详情"的方法都拆成两步:
 * 先用主库做 ANN 拿候选 {@code ad_id},再用分片库取 ad/advertiser 详情与出价(避免 single⋈sharded 跨源 JOIN
 * 与把 {@code <=>} 喂进 ShardingSphere 解析器)。各方法只读、失败返回空,降级判断留给上层召回服务。
 */
@Repository
public class AdRepository {

    private static final Logger log = LoggerFactory.getLogger(AdRepository.class);

    /** 主数据源:普通 Postgres(ad_embedding ANN / user_embedding 等 pgvector,行为不变)。 */
    private final JdbcTemplate jdbc;
    /** 次数据源:ShardingSphere,读分片广告表(advertiser/ad/bidword/ad_creative)。 */
    private final JdbcTemplate sharded;

    public AdRepository(JdbcTemplate jdbc, @Qualifier("adShardingJdbc") JdbcTemplate sharded) {
        this.jdbc = jdbc;
        this.sharded = sharded;
    }

    /**
     * 关键词召回(DB 兜底路):命中 {@code bidword.keyword ∈ keywords} 且广告/广告主 active 的候选。
     * 分库:先查 bidword(按 ad_id 分片,keyword 过滤 → 广播合并),再按 ad_id 取 active 广告详情。
     */
    public List<AdCandidate> kwByDb(Collection<String> keywords, Set<String> exactTerms, int limit) {
        if (keywords.isEmpty()) {
            return List.of();
        }
        String[] kws = keywords.toArray(new String[0]);
        try {
            // 1) 命中竞价词(ad_id, bidwordId, keyword, bid)
            List<long[]> hits = new ArrayList<>();          // [adId, bidwordId]
            List<String> hitKw = new ArrayList<>();
            List<Double> hitBid = new ArrayList<>();
            sharded.query(
                    "SELECT ad_id, id AS bidword_id, keyword, bid FROM bidword " +
                    "WHERE keyword = ANY(?) ORDER BY bid DESC LIMIT ?",
                    ps -> {
                        ps.setArray(1, ps.getConnection().createArrayOf("text", kws));
                        ps.setInt(2, limit);
                    },
                    rs -> {
                        hits.add(new long[]{rs.getLong("ad_id"), rs.getLong("bidword_id")});
                        hitKw.add(rs.getString("keyword"));
                        hitBid.add(rs.getDouble("bid"));
                    });
            if (hits.isEmpty()) {
                return List.of();
            }
            // 2) 取 active 广告详情(广告/广告主 active)
            Map<Long, AdDetail> details = activeAdDetails(adIdSet(hits));
            List<AdCandidate> out = new ArrayList<>(hits.size());
            for (int i = 0; i < hits.size(); i++) {
                long adId = hits.get(i)[0];
                AdDetail d = details.get(adId);
                if (d == null) {
                    continue; // 广告/广告主非 active
                }
                double bid = hitBid.get(i);
                AdChannel ch = exactTerms.contains(hitKw.get(i)) ? AdChannel.KW_EXACT : AdChannel.KW_BROAD;
                out.add(new AdCandidate(adId, d.itemId(), d.advertiserId(), hits.get(i)[1],
                        bid, ch, bid, d.quality()));
            }
            return out;
        } catch (Exception e) {
            log.warn("关键词召回(DB)失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 语义召回:query 向量 → ad_embedding 余弦 ANN(主库直查),再取 active 广告详情 + 出价(分片库)。
     * embedding 为 null 时上层不应调用本方法。
     */
    public List<AdCandidate> semantic(float[] queryVec, int limit) {
        return annThenDetails(queryVec, limit, AdChannel.SEMANTIC_AD);
    }

    /**
     * U2A 定向召回(docs/05 §4.2):用户长期兴趣向量 → ad_embedding 余弦 ANN。query 无关的个性化补充。
     * 新用户无向量 → 空。
     */
    public List<AdCandidate> u2a(long userId, int limit) {
        float[] userVec = loadUserVector(userId);
        if (userVec == null) {
            return List.of();
        }
        return annThenDetails(userVec, limit, AdChannel.U2A);
    }

    /** ANN(主库 ad_embedding)→ 详情/出价(分片库)。recallScore=余弦相似度,保持 ANN 名次。 */
    private List<AdCandidate> annThenDetails(float[] vec, int limit, AdChannel channel) {
        try {
            // 1) 主库做 ad_embedding 余弦 ANN(ad_embedding 不分片,全量在 ds_0)
            PGvector pv = new PGvector(vec);
            LinkedHashMap<Long, Double> sims = new LinkedHashMap<>(); // adId → sim(保序)
            jdbc.query(
                    "SELECT ad_id, 1 - (embedding <=> ?) AS sim FROM ad_embedding " +
                    "ORDER BY embedding <=> ? LIMIT ?",
                    ps -> {
                        ps.setObject(1, pv);
                        ps.setObject(2, pv);
                        ps.setInt(3, limit);
                    },
                    rs -> { sims.put(rs.getLong("ad_id"), rs.getDouble("sim")); });
            if (sims.isEmpty()) {
                return List.of();
            }
            // 2) 分片库取 active 详情 + 出价
            Map<Long, AdDetail> details = activeAdDetails(sims.keySet());
            Map<Long, Double> bids = bidMap(sims.keySet());
            List<AdCandidate> out = new ArrayList<>(sims.size());
            for (Map.Entry<Long, Double> e : sims.entrySet()) {
                long adId = e.getKey();
                AdDetail d = details.get(adId);
                if (d == null) {
                    continue;
                }
                double bid = bids.getOrDefault(adId, 0.0);
                out.add(new AdCandidate(adId, d.itemId(), d.advertiserId(), 0L,
                        e.getValue(), channel, bid, d.quality()));
            }
            return out;
        } catch (Exception e) {
            log.warn("{} 广告召回失败: {}", channel, e.getMessage());
            return List.of();
        }
    }

    /** 兜底召回:按 quality × 最高出价取头部活跃广告。分库:取 active 广告(分片) + 出价聚合(分片),Java 排序截断。 */
    public List<AdCandidate> hot(int limit) {
        try {
            Map<Long, AdDetail> ads = activeAdDetails(null); // null = 全部 active
            if (ads.isEmpty()) {
                return List.of();
            }
            Map<Long, Double> bids = bidMap(null);
            List<AdCandidate> all = new ArrayList<>(ads.size());
            for (Map.Entry<Long, AdDetail> e : ads.entrySet()) {
                long adId = e.getKey();
                AdDetail d = e.getValue();
                double bid = bids.getOrDefault(adId, 0.0);
                all.add(new AdCandidate(adId, d.itemId(), d.advertiserId(), 0L,
                        d.quality() * bid, AdChannel.HOT_AD, bid, d.quality()));
            }
            all.sort((a, b) -> Double.compare(b.recallScore(), a.recallScore()));
            return all.size() > limit ? all.subList(0, limit) : all;
        } catch (Exception e) {
            log.warn("兜底广告召回失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 取 active 广告(广告 active 且广告主非 paused)详情。{@code adIds=null} 取全部;否则按 ad_id 过滤。
     * ad ⋈ advertiser 同按 advertiser_id 分库(绑定表)→ 单库内 JOIN、不跨库笛卡尔积。
     */
    private Map<Long, AdDetail> activeAdDetails(Collection<Long> adIds) {
        Map<Long, AdDetail> out = new HashMap<>();
        String base =
                "SELECT a.ad_id, a.item_id, a.advertiser_id, a.quality_score " +
                "FROM ad a JOIN advertiser adv ON adv.advertiser_id = a.advertiser_id AND adv.status <> 'paused' " +
                "WHERE a.status = 'active'";
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

    /** 各广告最高出价。{@code adIds=null} 取全部;否则按 ad_id 过滤(bidword 按 ad_id 分库 → 路由/广播)。 */
    private Map<Long, Double> bidMap(Collection<Long> adIds) {
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

    private static java.util.Set<Long> adIdSet(List<long[]> hits) {
        java.util.Set<Long> s = new java.util.HashSet<>();
        for (long[] h : hits) {
            s.add(h[0]);
        }
        return s;
    }

    /**
     * 批量取广告的 oCPC 参数(adId → {优化方式, 目标 CPA})。ad 按 advertiser_id 分库,按 ad_id 过滤 → 广播。
     * 缺列/失败返回空 map。
     */
    public java.util.Map<Long, OcpcParams> ocpcParams(Collection<Long> adIds) {
        if (adIds.isEmpty()) {
            return java.util.Map.of();
        }
        Long[] ids = adIds.toArray(new Long[0]);
        java.util.Map<Long, OcpcParams> out = new java.util.HashMap<>();
        try {
            sharded.query(
                    "SELECT ad_id, optimization_type, target_cpa FROM ad WHERE ad_id = ANY(?)",
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids)),
                    rs -> {
                        String type = rs.getString("optimization_type");
                        double cpa = rs.getDouble("target_cpa");
                        out.put(rs.getLong("ad_id"),
                                new OcpcParams(type == null ? "CPC" : type, rs.wasNull() ? 0.0 : cpa));
                    });
        } catch (Exception e) {
            log.warn("取广告 oCPC 参数失败,退 CPC: {}", e.getMessage());
        }
        return out;
    }

    /** 广告的出价优化方式与目标转化成本。 */
    public record OcpcParams(String optimizationType, double targetCpa) {
        public boolean isOcpc() {
            return "OCPC".equalsIgnoreCase(optimizationType) && targetCpa > 0;
        }
    }

    /** 批量取广告创意标题(竞得后给前几条填标题)。ad 按 ad_id 过滤 → 广播。 */
    public java.util.Map<Long, String> titles(Collection<Long> adIds) {
        if (adIds.isEmpty()) {
            return java.util.Map.of();
        }
        Long[] ids = adIds.toArray(new Long[0]);
        java.util.Map<Long, String> out = new java.util.HashMap<>();
        try {
            sharded.query(
                    "SELECT ad_id, title FROM ad WHERE ad_id = ANY(?)",
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids)),
                    rs -> { out.put(rs.getLong("ad_id"), rs.getString("title")); });
        } catch (Exception e) {
            log.warn("取广告标题失败: {}", e.getMessage());
        }
        return out;
    }

    /**
     * 读用户长期兴趣向量(口径同推荐 VECTOR 通道,主库 user_embedding)。PGvector 类型未注册时降级文本解析;
     * 新用户/无向量返回 null。
     */
    private float[] loadUserVector(long userId) {
        try {
            List<PGvector> rows = jdbc.query(
                    "SELECT embedding FROM user_embedding WHERE user_id=?",
                    (rs, n) -> (PGvector) rs.getObject("embedding"), userId);
            if (rows.isEmpty() || rows.get(0) == null) {
                return null;
            }
            return rows.get(0).toArray();
        } catch (Exception e) {
            log.debug("读取 user_embedding 失败,尝试文本解析: {}", e.getMessage());
            try {
                List<String> rows = jdbc.query(
                        "SELECT embedding::text FROM user_embedding WHERE user_id=?",
                        (rs, n) -> rs.getString(1), userId);
                if (rows.isEmpty() || rows.get(0) == null) {
                    return null;
                }
                String[] parts = rows.get(0).replace("[", "").replace("]", "").split(",");
                float[] v = new float[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    v[i] = Float.parseFloat(parts[i].trim());
                }
                return v;
            } catch (Exception ex) {
                log.debug("文本解析 user_embedding 也失败: {}", ex.getMessage());
                return null;
            }
        }
    }

    /** 广告详情(ANN/关键词召回后回填用)。 */
    private record AdDetail(long itemId, long advertiserId, double quality) {
    }
}
