package com.recsys.ad;

import com.pgvector.PGvector;
import com.recsys.common.ad.AdCandidate;
import com.recsys.common.ad.AdChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 广告库读取(JDBC)。ad / bidword / advertiser / ad_embedding 的真源访问。
 * 各方法只读、失败返回空,把降级判断留给上层召回服务。
 */
@Repository
public class AdRepository {

    private static final Logger log = LoggerFactory.getLogger(AdRepository.class);

    private final JdbcTemplate jdbc;

    public AdRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 关键词召回(DB 兜底路,Redis 倒排不可用时走这里):
     * 命中 {@code bidword.keyword ∈ keywords} 且广告/广告主 active 的候选。
     * channel 由 keyword 是否属于 exactTerms 区分:命中原始词项 → KW_EXACT,否则(改写/同义)→ KW_BROAD。
     */
    public List<AdCandidate> kwByDb(Collection<String> keywords, Set<String> exactTerms, int limit) {
        if (keywords.isEmpty()) {
            return List.of();
        }
        String[] kws = keywords.toArray(new String[0]);
        try {
            return jdbc.query(
                    "SELECT b.ad_id, a.item_id, a.advertiser_id, b.id AS bidword_id, " +
                    "       b.keyword, b.bid, a.quality_score " +
                    "FROM bidword b " +
                    "JOIN ad a ON a.ad_id = b.ad_id AND a.status = 'active' " +
                    "JOIN advertiser adv ON adv.advertiser_id = a.advertiser_id AND adv.status <> 'paused' " +
                    "WHERE b.keyword = ANY(?) " +
                    "ORDER BY b.bid DESC LIMIT ?",
                    ps -> {
                        ps.setArray(1, ps.getConnection().createArrayOf("text", kws));
                        ps.setInt(2, limit);
                    },
                    (rs, n) -> {
                        String kw = rs.getString("keyword");
                        AdChannel ch = exactTerms.contains(kw) ? AdChannel.KW_EXACT : AdChannel.KW_BROAD;
                        return new AdCandidate(
                                rs.getLong("ad_id"), rs.getLong("item_id"), rs.getLong("advertiser_id"),
                                rs.getLong("bidword_id"), rs.getDouble("bid"), ch,
                                rs.getDouble("bid"), rs.getDouble("quality_score"));
                    });
        } catch (Exception e) {
            log.warn("关键词召回(DB)失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 语义召回:query 向量 → ad_embedding 余弦 ANN。recallScore=余弦相似度,
     * bid 取该广告竞价词的最高出价(无竞价词则 0,后续兜底)。embedding 为 null 时上层不应调用本方法。
     */
    public List<AdCandidate> semantic(float[] queryVec, int limit) {
        try {
            PGvector pv = new PGvector(queryVec);
            return jdbc.query(
                    "SELECT a.ad_id, a.item_id, a.advertiser_id, a.quality_score, " +
                    "       1 - (e.embedding <=> ?) AS sim, " +
                    "       COALESCE((SELECT MAX(b.bid) FROM bidword b WHERE b.ad_id = a.ad_id), 0) AS bid " +
                    "FROM ad_embedding e " +
                    "JOIN ad a ON a.ad_id = e.ad_id AND a.status = 'active' " +
                    "JOIN advertiser adv ON adv.advertiser_id = a.advertiser_id AND adv.status <> 'paused' " +
                    "ORDER BY e.embedding <=> ? LIMIT ?",
                    ps -> {
                        ps.setObject(1, pv);
                        ps.setObject(2, pv);
                        ps.setInt(3, limit);
                    },
                    (rs, n) -> new AdCandidate(
                            rs.getLong("ad_id"), rs.getLong("item_id"), rs.getLong("advertiser_id"), 0L,
                            rs.getDouble("sim"), AdChannel.SEMANTIC_AD,
                            rs.getDouble("bid"), rs.getDouble("quality_score")));
        } catch (Exception e) {
            log.warn("语义广告召回失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 批量取广告创意标题(竞得后给前几条填标题)。 */
    public java.util.Map<Long, String> titles(Collection<Long> adIds) {
        if (adIds.isEmpty()) {
            return java.util.Map.of();
        }
        Long[] ids = adIds.toArray(new Long[0]);
        java.util.Map<Long, String> out = new java.util.HashMap<>();
        try {
            jdbc.query(
                    "SELECT ad_id, title FROM ad WHERE ad_id = ANY(?)",
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids)),
                    (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                            out.put(rs.getLong("ad_id"), rs.getString("title")));
        } catch (Exception e) {
            log.warn("取广告标题失败: {}", e.getMessage());
        }
        return out;
    }

    /**
     * 兜底召回:按 quality × 最高出价(eCPM 上界近似)取头部活跃广告,保填充率。
     */
    public List<AdCandidate> hot(int limit) {
        try {
            return jdbc.query(
                    "SELECT a.ad_id, a.item_id, a.advertiser_id, a.quality_score, " +
                    "       COALESCE(MAX(b.bid), 0) AS bid " +
                    "FROM ad a " +
                    "JOIN advertiser adv ON adv.advertiser_id = a.advertiser_id AND adv.status <> 'paused' " +
                    "LEFT JOIN bidword b ON b.ad_id = a.ad_id " +
                    "WHERE a.status = 'active' " +
                    "GROUP BY a.ad_id, a.item_id, a.advertiser_id, a.quality_score " +
                    "ORDER BY a.quality_score * COALESCE(MAX(b.bid), 0) DESC LIMIT ?",
                    (rs, n) -> new AdCandidate(
                            rs.getLong("ad_id"), rs.getLong("item_id"), rs.getLong("advertiser_id"), 0L,
                            rs.getDouble("quality_score") * rs.getDouble("bid"), AdChannel.HOT_AD,
                            rs.getDouble("bid"), rs.getDouble("quality_score")),
                    limit);
        } catch (Exception e) {
            log.warn("兜底广告召回失败: {}", e.getMessage());
            return List.of();
        }
    }
}
