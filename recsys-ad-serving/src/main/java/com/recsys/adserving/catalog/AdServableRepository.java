package com.recsys.adserving.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.ad.AdCatalogReader;
import com.recsys.ad.AdRepository;
import com.recsys.common.ad.AdCatalogEvent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 广告可服务副本读写(P1b + 收尾)。ad-serving <b>自有</b>的目录副本 {@code ad_servable}(主数据源=recsys 库,非分片),
 * 由 {@link AdCatalogEventConsumer} 从广告目录事件幂等构建。{@code ReplicaAdCatalogReader} 经本类提供
 * 广告在线所需的<b>全部目录读</b>(标题 / oCPC / 可服务详情 / 最高出价 / 定向人群),使 ad-serving 不再跨上下文
 * 直读广告主分片目录库(DB-per-service)。
 *
 * <p>只存可服务广告:{@code servable=false} → {@link #delete(long)} 移除行,故副本即"可服务集",读侧无需再过滤状态。
 * {@code max_bid} 消费时从 bidwords 算好、免读时解析 JSON。用主 {@link JdbcTemplate}(非 ShardingSphere)。
 */
@Repository
public class AdServableRepository {

    private static final Logger log = LoggerFactory.getLogger(AdServableRepository.class);

    private final JdbcTemplate jdbc;   // #3:adDbJdbc —— ad_servable 走 ad-serving 自有库(默认 recsys,AD_PG_DB 设则拆库)
    private final ObjectMapper mapper = new ObjectMapper();

    public AdServableRepository(@Qualifier("adDbJdbc") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 防御性自建表(已建库实例无需重跑 08_ad_servable.sql 也可用)。 */
    @PostConstruct
    void ensureTable() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS ad_servable (
                        ad_id BIGINT PRIMARY KEY, advertiser_id BIGINT NOT NULL, item_id BIGINT NOT NULL,
                        title TEXT, landing_url TEXT, quality_score DOUBLE PRECISION, status TEXT,
                        review_status TEXT, optimization_type TEXT, target_cpa DOUBLE PRECISION,
                        audience_id BIGINT, max_bid DOUBLE PRECISION DEFAULT 0,
                        servable BOOLEAN NOT NULL DEFAULT TRUE, bidwords_json TEXT, creatives_json TEXT,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now())""");
            // 兼容旧表(schema 演进):CREATE TABLE IF NOT EXISTS 不给已存在的表加列,故显式补 P1b 收尾新增的列。
            jdbc.execute("ALTER TABLE ad_servable ADD COLUMN IF NOT EXISTS audience_id BIGINT");
            jdbc.execute("ALTER TABLE ad_servable ADD COLUMN IF NOT EXISTS max_bid DOUBLE PRECISION DEFAULT 0");
            // #3 拆库:ad_embedding 也归 ad-serving 自有库(adDbJdbc),由目录事件带向量灌;防御性自建。
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
            jdbc.execute("CREATE TABLE IF NOT EXISTS ad_embedding "
                    + "(ad_id BIGINT PRIMARY KEY, embedding vector(768), model TEXT)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ad_embedding_hnsw ON ad_embedding "
                    + "USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 200)");
        } catch (Exception e) {
            log.warn("建 ad_servable/ad_embedding 表失败(稍后消费/读会再暴露): {}", e.getMessage());
        }
    }

    /** #3:从目录事件携带的向量(pgvector ::text)幂等 upsert ad_embedding(拆库后 ad_embedding 归 ad-serving 自有库)。 */
    public void upsertEmbedding(long adId, String vecText) {
        if (vecText == null || vecText.isBlank()) {
            return;
        }
        try {
            jdbc.update("INSERT INTO ad_embedding(ad_id, embedding, model) VALUES(?, CAST(? AS vector), 'catalog-event') "
                    + "ON CONFLICT (ad_id) DO UPDATE SET embedding=EXCLUDED.embedding, model=EXCLUDED.model",
                    adId, vecText);
        } catch (Exception e) {
            log.warn("upsert ad_embedding 失败 adId={}: {}", adId, e.getMessage());
        }
    }

    public void deleteEmbedding(long adId) {
        try {
            jdbc.update("DELETE FROM ad_embedding WHERE ad_id=?", adId);
        } catch (Exception e) {
            log.debug("delete ad_embedding 失败 adId={}: {}", adId, e.getMessage());
        }
    }

    /** 幂等 upsert 一条可服务广告快照(max_bid 从 bidwords 算)。 */
    public void upsert(AdCatalogEvent e) {
        double maxBid = e.bidwords().stream().mapToDouble(AdCatalogEvent.Bidword::bid).max().orElse(0.0);
        try {
            jdbc.update("""
                    INSERT INTO ad_servable(ad_id,advertiser_id,item_id,title,landing_url,quality_score,
                        status,review_status,optimization_type,target_cpa,audience_id,max_bid,servable,
                        bidwords_json,creatives_json,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,TRUE,?,?,now())
                    ON CONFLICT (ad_id) DO UPDATE SET
                        advertiser_id=EXCLUDED.advertiser_id, item_id=EXCLUDED.item_id, title=EXCLUDED.title,
                        landing_url=EXCLUDED.landing_url, quality_score=EXCLUDED.quality_score, status=EXCLUDED.status,
                        review_status=EXCLUDED.review_status, optimization_type=EXCLUDED.optimization_type,
                        target_cpa=EXCLUDED.target_cpa, audience_id=EXCLUDED.audience_id, max_bid=EXCLUDED.max_bid,
                        servable=TRUE, bidwords_json=EXCLUDED.bidwords_json, creatives_json=EXCLUDED.creatives_json,
                        updated_at=now()""",
                    e.adId(), e.advertiserId(), e.itemId(), e.title(), e.landingUrl(), e.qualityScore(),
                    e.status(), e.reviewStatus(), e.optimizationType(), e.targetCpa(), e.audienceId(), maxBid,
                    writeJson(e.bidwords()), writeJson(e.creatives()));
        } catch (Exception ex) {
            log.warn("upsert ad_servable 失败 adId={}: {}", e.adId(), ex.getMessage());
        }
    }

    /** 读该广告当前副本里的竞价词(用于消费端维护倒排时算关键词 diff)。行不存在/解析失败 → 空。 */
    public List<AdCatalogEvent.Bidword> bidwordsOf(long adId) {
        try {
            List<String> rows = jdbc.query("SELECT bidwords_json FROM ad_servable WHERE ad_id=?",
                    (rs, n) -> rs.getString("bidwords_json"), adId);
            if (rows.isEmpty() || rows.get(0) == null) {
                return List.of();
            }
            return mapper.readValue(rows.get(0), new TypeReference<List<AdCatalogEvent.Bidword>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 移除广告(下线/删除/未过审)。 */
    public void delete(long adId) {
        try {
            jdbc.update("DELETE FROM ad_servable WHERE ad_id=?", adId);
        } catch (Exception ex) {
            log.warn("删 ad_servable 失败 adId={}: {}", adId, ex.getMessage());
        }
    }

    /** 副本里的可服务广告数(供验证)。 */
    public long count() {
        try {
            Long n = jdbc.queryForObject("SELECT COUNT(*) FROM ad_servable", Long.class);
            return n == null ? 0 : n;
        } catch (Exception ex) {
            return -1;
        }
    }

    public Map<Long, String> titles(Collection<Long> adIds) {
        Map<Long, String> out = new HashMap<>();
        if (adIds.isEmpty()) {
            return out;
        }
        Long[] ids = adIds.toArray(new Long[0]);
        try {
            jdbc.query("SELECT ad_id, title FROM ad_servable WHERE ad_id = ANY(?)",
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids)),
                    rs -> { out.put(rs.getLong("ad_id"), rs.getString("title")); });
        } catch (Exception ex) {
            log.warn("读 ad_servable 标题失败: {}", ex.getMessage());
        }
        return out;
    }

    public Map<Long, AdRepository.OcpcParams> ocpcParams(Collection<Long> adIds) {
        Map<Long, AdRepository.OcpcParams> out = new HashMap<>();
        if (adIds.isEmpty()) {
            return out;
        }
        Long[] ids = adIds.toArray(new Long[0]);
        try {
            jdbc.query("SELECT ad_id, optimization_type, target_cpa FROM ad_servable WHERE ad_id = ANY(?)",
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids)),
                    rs -> {
                        String type = rs.getString("optimization_type");
                        double cpa = rs.getDouble("target_cpa");
                        out.put(rs.getLong("ad_id"),
                                new AdRepository.OcpcParams(type == null ? "CPC" : type, rs.wasNull() ? 0.0 : cpa));
                    });
        } catch (Exception ex) {
            log.warn("读 ad_servable oCPC 参数失败: {}", ex.getMessage());
        }
        return out;
    }

    /** 可服务详情。adIds=null → 全部(副本仅存可服务广告,故无需再过滤状态)。 */
    public Map<Long, AdCatalogReader.AdDetail> activeAdDetails(Collection<Long> adIds) {
        Map<Long, AdCatalogReader.AdDetail> out = new HashMap<>();
        try {
            String base = "SELECT ad_id, item_id, advertiser_id, quality_score FROM ad_servable";
            if (adIds == null) {
                jdbc.query(base, rs -> { putDetail(out, rs); });
            } else {
                if (adIds.isEmpty()) {
                    return out;
                }
                Long[] ids = adIds.toArray(new Long[0]);
                jdbc.query(base + " WHERE ad_id = ANY(?)",
                        ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids)),
                        rs -> { putDetail(out, rs); });
            }
        } catch (Exception ex) {
            log.warn("读 ad_servable 详情失败: {}", ex.getMessage());
        }
        return out;
    }

    private static void putDetail(Map<Long, AdCatalogReader.AdDetail> out, java.sql.ResultSet rs)
            throws java.sql.SQLException {
        out.put(rs.getLong("ad_id"), new AdCatalogReader.AdDetail(
                rs.getLong("item_id"), rs.getLong("advertiser_id"), rs.getDouble("quality_score")));
    }

    /** 最高出价(消费时已算好存 max_bid 列)。adIds=null → 全部。 */
    public Map<Long, Double> bidMap(Collection<Long> adIds) {
        Map<Long, Double> out = new HashMap<>();
        try {
            String base = "SELECT ad_id, max_bid FROM ad_servable";
            if (adIds == null) {
                jdbc.query(base, rs -> { out.put(rs.getLong("ad_id"), rs.getDouble("max_bid")); });
            } else {
                if (adIds.isEmpty()) {
                    return out;
                }
                Long[] ids = adIds.toArray(new Long[0]);
                jdbc.query(base + " WHERE ad_id = ANY(?)",
                        ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids)),
                        rs -> { out.put(rs.getLong("ad_id"), rs.getDouble("max_bid")); });
            }
        } catch (Exception ex) {
            log.warn("读 ad_servable 出价失败: {}", ex.getMessage());
        }
        return out;
    }

    /** 定向人群包(audience_id 非空的广告)。 */
    public Map<Long, Long> audiencesByAd(Collection<Long> adIds) {
        Map<Long, Long> out = new HashMap<>();
        if (adIds == null || adIds.isEmpty()) {
            return out;
        }
        Long[] ids = adIds.toArray(new Long[0]);
        try {
            jdbc.query("SELECT ad_id, audience_id FROM ad_servable WHERE audience_id IS NOT NULL AND ad_id = ANY(?)",
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids)),
                    rs -> { out.put(rs.getLong("ad_id"), rs.getLong("audience_id")); });
        } catch (Exception ex) {
            log.warn("读 ad_servable 定向失败: {}", ex.getMessage());
        }
        return out;
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }
}
