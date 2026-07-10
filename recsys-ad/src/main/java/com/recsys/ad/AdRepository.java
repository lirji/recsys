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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 广告召回读取(JDBC)。负责<b>向量 ANN(ad_embedding,主库不分片)+ user_embedding</b> 的真源访问,
 * 以及关键词 DB 兜底召回;广告<b>目录详情/出价</b>的读则委托 {@link AdCatalogReader}
 * (sharded 直读分片库 / replica 读事件副本,P1b)。
 *
 * <p>凡"向量 ANN + 广告详情"的召回都两步:主库做 {@code <=>} ANN 拿候选 {@code ad_id},再经
 * {@link AdCatalogReader#activeAdDetails}/{@link AdCatalogReader#bidMap} 回填详情与出价——这样详情读可在
 * sharded/replica 间切换,而 pgvector 非标准算子始终走主库、不进 ShardingSphere 解析路径。各方法只读、失败返回空。
 *
 * <p><b>残留</b>:{@link #kwByDb} 的关键词→广告匹配仍扫描分片 {@code bidword}(关键词召回的 DB 兜底路;
 * 主路走 Redis {@code bidword:inv},P1b 收尾已由 ad-serving 消费端维护)。其详情回填已走 {@link AdCatalogReader}。
 */
@Repository
public class AdRepository {

    private static final Logger log = LoggerFactory.getLogger(AdRepository.class);

    /** 主数据源:普通 Postgres —— 仅 user_embedding(共享读,留主库)pgvector。 */
    private final JdbcTemplate jdbc;
    /** #3:ad_embedding(ad-serving 自有)专用源 —— 默认 recsys,AD_PG_DB 设则拆库。 */
    private final JdbcTemplate adDb;
    /** 次数据源:ShardingSphere —— 仅 kwByDb 的 bidword 关键词扫描仍用。 */
    private final JdbcTemplate sharded;
    /** 目录读(标题/oCPC/详情/出价/定向):sharded 或 replica。 */
    private final AdCatalogReader catalog;

    public AdRepository(JdbcTemplate jdbc, @Qualifier("adDbJdbc") JdbcTemplate adDb,
                        @Qualifier("adShardingJdbc") JdbcTemplate sharded, AdCatalogReader catalog) {
        this.jdbc = jdbc;
        this.adDb = adDb;
        this.sharded = sharded;
        this.catalog = catalog;
    }

    /**
     * 关键词召回(DB 兜底路):命中 {@code bidword.keyword ∈ keywords} 的候选;详情回填走 {@link AdCatalogReader}
     * (故其可服务口径与 replica 一致)。bidword 关键词扫描仍在分片库(残留兜底路)。
     */
    public List<AdCandidate> kwByDb(Collection<String> keywords, Set<String> exactTerms, int limit) {
        if (keywords.isEmpty()) {
            return List.of();
        }
        String[] kws = keywords.toArray(new String[0]);
        try {
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
            Map<Long, AdCatalogReader.AdDetail> details = catalog.activeAdDetails(adIdSet(hits));
            List<AdCandidate> out = new ArrayList<>(hits.size());
            for (int i = 0; i < hits.size(); i++) {
                long adId = hits.get(i)[0];
                AdCatalogReader.AdDetail d = details.get(adId);
                if (d == null) {
                    continue; // 广告/广告主非可服务
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

    /** 语义召回:query 向量 → ad_embedding 余弦 ANN(主库直查),再取可服务详情 + 出价(目录读)。 */
    public List<AdCandidate> semantic(float[] queryVec, int limit) {
        return annThenDetails(queryVec, limit, AdChannel.SEMANTIC_AD);
    }

    /** U2A 定向召回:用户长期兴趣向量 → ad_embedding 余弦 ANN。新用户无向量 → 空。 */
    public List<AdCandidate> u2a(long userId, int limit) {
        float[] userVec = loadUserVector(userId);
        if (userVec == null) {
            return List.of();
        }
        return annThenDetails(userVec, limit, AdChannel.U2A);
    }

    /** ANN(主库 ad_embedding)→ 详情/出价(目录读)。recallScore=余弦相似度,保持 ANN 名次。 */
    private List<AdCandidate> annThenDetails(float[] vec, int limit, AdChannel channel) {
        try {
            PGvector pv = new PGvector(vec);
            LinkedHashMap<Long, Double> sims = new LinkedHashMap<>(); // adId → sim(保序)
            adDb.query(
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
            Map<Long, AdCatalogReader.AdDetail> details = catalog.activeAdDetails(sims.keySet());
            Map<Long, Double> bids = catalog.bidMap(sims.keySet());
            List<AdCandidate> out = new ArrayList<>(sims.size());
            for (Map.Entry<Long, Double> e : sims.entrySet()) {
                long adId = e.getKey();
                AdCatalogReader.AdDetail d = details.get(adId);
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

    /** 兜底召回:按 quality × 最高出价取头部可服务广告(全量可服务详情 + 出价来自目录读)。 */
    public List<AdCandidate> hot(int limit) {
        try {
            Map<Long, AdCatalogReader.AdDetail> ads = catalog.activeAdDetails(null); // null = 全部可服务
            if (ads.isEmpty()) {
                return List.of();
            }
            Map<Long, Double> bids = catalog.bidMap(null);
            List<AdCandidate> all = new ArrayList<>(ads.size());
            for (Map.Entry<Long, AdCatalogReader.AdDetail> e : ads.entrySet()) {
                long adId = e.getKey();
                AdCatalogReader.AdDetail d = e.getValue();
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

    private static Set<Long> adIdSet(List<long[]> hits) {
        Set<Long> s = new java.util.HashSet<>();
        for (long[] h : hits) {
            s.add(h[0]);
        }
        return s;
    }

    /** 广告的出价优化方式与目标转化成本。 */
    public record OcpcParams(String optimizationType, double targetCpa) {
        public boolean isOcpc() {
            return "OCPC".equalsIgnoreCase(optimizationType) && targetCpa > 0;
        }
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
}
