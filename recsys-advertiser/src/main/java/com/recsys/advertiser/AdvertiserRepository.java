package com.recsys.advertiser;

import com.recsys.advertiser.dto.AdReportRow;
import com.recsys.advertiser.dto.AdView;
import com.recsys.advertiser.dto.AdvertiserView;
import com.recsys.advertiser.dto.BidwordView;
import com.recsys.advertiser.dto.CreativeView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 广告主侧真源读写(JDBC):advertiser / ad / ad_creative / bidword / ad_embedding。
 *
 * <p>只负责库内一致性;Redis 倒排与向量的在线同步在 {@link AdvertiserService} 编排
 * (借 {@link AdEmbeddingRepository} 等),因此本类不感知 Redis。
 * <p>主键全部由 <b>ShardingSphere 的 Snowflake keyGenerator</b> 生成(见 sharding.yaml):insert 不带主键列,
 * ShardingSphere 注入 8 字节、时间有序、去中心化的 id,并按分片键路由到 ds_0 / ds_1;生成值经
 * {@code Statement.getGeneratedKeys()}({@link KeyHolder})回传。advertiser 按 advertiser_id 分库,
 * ad/bidword/ad_creative 按 ad_id 分库(三者绑定)。这替代了单库 IDENTITY——分库后跨库自增会冲突。
 */
@Repository
public class AdvertiserRepository {

    private final JdbcTemplate jdbc;
    private final JdbcTemplate derived;   // #3:item_embedding 读走 rec-serving 派生库(默认 recsys)
    private final AdReportReader reportReader;   // #3:ad_event 聚合来源(db 直读 / grpc 调 ad-serving)

    public AdvertiserRepository(JdbcTemplate jdbc,
                                @org.springframework.beans.factory.annotation.Qualifier("derivedJdbc") JdbcTemplate derived,
                                AdReportReader reportReader) {
        this.jdbc = jdbc;
        this.derived = derived;
        this.reportReader = reportReader;
    }

    // ---------------- advertiser ----------------

    /** 插入广告主,advertiser_id 由 ShardingSphere Snowflake 生成、getGeneratedKeys 回传。 */
    public long insertAdvertiser(String name, double dailyBudget, String status) {
        return insertReturningKey("advertiser_id",
                "INSERT INTO advertiser(name,daily_budget,status) VALUES(?,?,?)",
                name, dailyBudget, status);
    }

    /**
     * 执行 insert 并回传 ShardingSphere 生成的主键(Snowflake)。不在 SQL 里写主键列,由 keyGenerator 注入;
     * 经 {@code prepareStatement(sql, new String[]{keyColumn})} + {@link KeyHolder} 取回生成值。
     */
    private long insertReturningKey(String keyColumn, String sql, Object... args) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{keyColumn});
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? 0L : key.longValue();
    }

    /** 部分更新:仅非 null 字段写库(COALESCE 保留原值)。返回受影响行数。 */
    public int updateAdvertiser(long id, String name, Double dailyBudget, String status) {
        return jdbc.update(
                "UPDATE advertiser SET name=COALESCE(?,name), daily_budget=COALESCE(?,daily_budget), " +
                "status=COALESCE(?,status) WHERE advertiser_id=?",
                name, dailyBudget, status, id);
    }

    public boolean advertiserExists(long id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM advertiser WHERE advertiser_id=?", Integer.class, id);
        return n != null && n > 0;
    }

    /** 不含预算消耗(spentToday/remaining 由 service 从 Redis 补)。 */
    public AdvertiserView findAdvertiserRaw(long id) {
        List<AdvertiserView> rows = jdbc.query(
                "SELECT advertiser_id,name,daily_budget,status FROM advertiser WHERE advertiser_id=?",
                (rs, n) -> new AdvertiserView(rs.getLong("advertiser_id"), rs.getString("name"),
                        rs.getDouble("daily_budget"), rs.getString("status"), 0, 0),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<AdvertiserView> listAdvertisersRaw() {
        return jdbc.query(
                "SELECT advertiser_id,name,daily_budget,status FROM advertiser ORDER BY advertiser_id",
                (rs, n) -> new AdvertiserView(rs.getLong("advertiser_id"), rs.getString("name"),
                        rs.getDouble("daily_budget"), rs.getString("status"), 0, 0));
    }

    // ---------------- ad ----------------

    /** 插入广告,ad_id 由 ShardingSphere Snowflake 生成、getGeneratedKeys 回传(分库键=ad_id)。 */
    public long insertAd(long advertiserId, long itemId, String title, String landingUrl,
                         double qualityScore, String status, String reviewStatus,
                         String optimizationType, Double targetCpa) {
        return insertReturningKey("ad_id",
                "INSERT INTO ad(advertiser_id,item_id,title,landing_url,quality_score,status," +
                        "review_status,optimization_type,target_cpa) VALUES(?,?,?,?,?,?,?,?,?)",
                advertiserId, itemId, title, landingUrl, qualityScore, status, reviewStatus,
                optimizationType, targetCpa);
    }

    public void setAdLandingUrl(long adId, String url) {
        jdbc.update("UPDATE ad SET landing_url=? WHERE ad_id=?", url, adId);
    }

    /** 部分更新。targetCpa 单独处理(null 也可能是"清空目标 CPA"的合法值,这里 COALESCE 表示不改)。 */
    public int updateAd(long adId, Long itemId, String title, String landingUrl, Double qualityScore,
                        String status, String optimizationType, Double targetCpa) {
        return jdbc.update(
                "UPDATE ad SET item_id=COALESCE(?,item_id), title=COALESCE(?,title), " +
                "landing_url=COALESCE(?,landing_url), quality_score=COALESCE(?,quality_score), " +
                "status=COALESCE(?,status), optimization_type=COALESCE(?,optimization_type), " +
                "target_cpa=COALESCE(?,target_cpa) WHERE ad_id=?",
                itemId, title, landingUrl, qualityScore, status, optimizationType, targetCpa, adId);
    }

    public int setAdStatus(long adId, String status) {
        return jdbc.update("UPDATE ad SET status=? WHERE ad_id=?", status, adId);
    }

    public boolean adExists(long adId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ad WHERE ad_id=?", Integer.class, adId);
        return n != null && n > 0;
    }

    public Long advertiserOfAd(long adId) {
        List<Long> rows = jdbc.query("SELECT advertiser_id FROM ad WHERE ad_id=?",
                (rs, n) -> rs.getLong("advertiser_id"), adId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 广告基础信息(不含子资源),供 service 装配视图 / reindex 取 itemId、status。
     * <p>分库后 {@code ad}(分片)与 {@code ad_embedding}(单表 ds_0)不能跨边界 JOIN —— 先按 ad_id 取广告
     * (分片内),再单查 ad_embedding 补 hasEmbedding 标志。
     */
    public AdRow findAdRow(long adId) {
        List<AdRow> rows = jdbc.query(SELECT_AD + " WHERE ad_id=?", (rs, n) -> mapAd(rs, false), adId);
        if (rows.isEmpty()) {
            return null;
        }
        return withEmbedding(rows.get(0), embeddingExists(adId));
    }

    public List<AdRow> listAdRows(long advertiserId) {
        List<AdRow> ads = jdbc.query(SELECT_AD + " WHERE advertiser_id=? ORDER BY ad_id",
                (rs, n) -> mapAd(rs, false), advertiserId);
        if (ads.isEmpty()) {
            return ads;
        }
        // ad_embedding 单表(ds_0):批量查哪些 ad 有向量
        Long[] ids = ads.stream().map(AdRow::adId).toArray(Long[]::new);
        java.util.Set<Long> withEmb = new java.util.HashSet<>();
        jdbc.query("SELECT ad_id FROM ad_embedding WHERE ad_id = ANY(?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids)),
                (java.sql.ResultSet rs) -> { withEmb.add(rs.getLong("ad_id")); });
        return ads.stream().map(r -> withEmbedding(r, withEmb.contains(r.adId()))).toList();
    }

    private static final String SELECT_AD =
            "SELECT ad_id,advertiser_id,item_id,title,landing_url,quality_score,status," +
            "review_status,optimization_type,target_cpa,audience_id FROM ad";

    private static AdRow mapAd(java.sql.ResultSet rs, boolean hasEmbedding) throws java.sql.SQLException {
        Double cpa = (Double) rs.getObject("target_cpa");
        Long aud = (Long) rs.getObject("audience_id");
        return new AdRow(rs.getLong("ad_id"), rs.getLong("advertiser_id"), rs.getLong("item_id"),
                rs.getString("title"), rs.getString("landing_url"), rs.getDouble("quality_score"),
                rs.getString("status"), rs.getString("review_status"),
                rs.getString("optimization_type"), cpa, aud, hasEmbedding);
    }

    private static AdRow withEmbedding(AdRow r, boolean hasEmbedding) {
        return new AdRow(r.adId(), r.advertiserId(), r.itemId(), r.title(), r.landingUrl(),
                r.qualityScore(), r.status(), r.reviewStatus(), r.optimizationType(), r.targetCpa(),
                r.audienceId(), hasEmbedding);
    }

    /** 审核决定落库(A2):设 review_status + review_reason。 */
    public int setAdReview(long adId, String reviewStatus, String reason) {
        return jdbc.update("UPDATE ad SET review_status=?, review_reason=? WHERE ad_id=?",
                reviewStatus, reason, adId);
    }

    private boolean embeddingExists(long adId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ad_embedding WHERE ad_id=?", Integer.class, adId);
        return n != null && n > 0;
    }

    /** 删除广告及其子资源(creative/bidword/embedding/事件保留作审计)。Redis 倒排清理在 service 层。 */
    public void deleteAd(long adId) {
        jdbc.update("DELETE FROM bidword WHERE ad_id=?", adId);
        jdbc.update("DELETE FROM ad_creative WHERE ad_id=?", adId);
        jdbc.update("DELETE FROM ad_embedding WHERE ad_id=?", adId);
        jdbc.update("DELETE FROM ad WHERE ad_id=?", adId);
    }

    public boolean itemExists(long itemId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM item WHERE item_id=?", Integer.class, itemId);
        return n != null && n > 0;
    }

    // ---------------- bidword ----------------

    public long insertBidword(long adId, String keyword, String matchType, double bid, String bidMode) {
        return insertReturningKey("id",
                "INSERT INTO bidword(ad_id,keyword,match_type,bid,bid_mode) VALUES(?,?,?,?,?)",
                adId, keyword, matchType, bid, bidMode);
    }

    public int updateBidword(long id, String keyword, String matchType, Double bid, String bidMode) {
        return jdbc.update(
                "UPDATE bidword SET keyword=COALESCE(?,keyword), match_type=COALESCE(?,match_type), " +
                "bid=COALESCE(?,bid), bid_mode=COALESCE(?,bid_mode) WHERE id=?",
                keyword, matchType, bid, bidMode, id);
    }

    public int deleteBidword(long id) {
        return jdbc.update("DELETE FROM bidword WHERE id=?", id);
    }

    public BidwordView findBidword(long id) {
        List<BidwordView> rows = jdbc.query(
                "SELECT id,ad_id,keyword,match_type,bid,bid_mode FROM bidword WHERE id=?",
                AdvertiserRepository::mapBidword, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<BidwordView> listBidwords(long adId) {
        return jdbc.query(
                "SELECT id,ad_id,keyword,match_type,bid,bid_mode FROM bidword WHERE ad_id=? ORDER BY id",
                AdvertiserRepository::mapBidword, adId);
    }

    private static BidwordView mapBidword(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new BidwordView(rs.getLong("id"), rs.getLong("ad_id"), rs.getString("keyword"),
                rs.getString("match_type"), rs.getDouble("bid"), rs.getString("bid_mode"));
    }

    // ---------------- ad_creative ----------------

    public long insertCreative(long adId, String title, String landingUrl, String status) {
        return insertReturningKey("creative_id",
                "INSERT INTO ad_creative(ad_id,title,landing_url,status) VALUES(?,?,?,?)",
                adId, title, landingUrl, status);
    }

    public int updateCreative(long creativeId, String title, String landingUrl, String status) {
        return jdbc.update(
                "UPDATE ad_creative SET title=COALESCE(?,title), landing_url=COALESCE(?,landing_url), " +
                "status=COALESCE(?,status) WHERE creative_id=?",
                title, landingUrl, status, creativeId);
    }

    public int deleteCreative(long creativeId) {
        return jdbc.update("DELETE FROM ad_creative WHERE creative_id=?", creativeId);
    }

    public Long adOfCreative(long creativeId) {
        List<Long> rows = jdbc.query("SELECT ad_id FROM ad_creative WHERE creative_id=?",
                (rs, n) -> rs.getLong("ad_id"), creativeId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<CreativeView> listCreatives(long adId) {
        return jdbc.query(
                "SELECT creative_id,ad_id,title,landing_url,status FROM ad_creative WHERE ad_id=? ORDER BY creative_id",
                (rs, n) -> new CreativeView(rs.getLong("creative_id"), rs.getLong("ad_id"),
                        rs.getString("title"), rs.getString("landing_url"), rs.getString("status")),
                adId);
    }

    // ---------------- ad_embedding(在线语义召回依赖)----------------

    /** 从 item_embedding 拷贝该广告关联 item 的向量(item 无向量则 0 行)。upsert 语义。
     *  #3 拆库:item_embedding(派生库)与 ad_embedding(分片库)可能不同库,拆读(derived,text 传输)+写(jdbc)。 */
    public int copyEmbeddingFromItem(long adId, long itemId) {
        List<String[]> v = derived.query(
                "SELECT embedding::text AS e, model FROM item_embedding WHERE item_id=?",
                (rs, n) -> new String[]{rs.getString("e"), rs.getString("model")}, itemId);
        if (v.isEmpty() || v.get(0)[0] == null) {
            return 0;
        }
        return jdbc.update(
                "INSERT INTO ad_embedding(ad_id, embedding, model) VALUES(?, CAST(? AS vector), ?) " +
                "ON CONFLICT (ad_id) DO UPDATE SET embedding=EXCLUDED.embedding, model=EXCLUDED.model",
                adId, v.get(0)[0], v.get(0)[1]);
    }

    public void deleteEmbedding(long adId) {
        jdbc.update("DELETE FROM ad_embedding WHERE ad_id=?", adId);
    }

    /** #3:读 item 向量(pgvector ::text 形式),供广告目录事件携带(拆库后 ad-serving 消费端写自有 ad_embedding)。null=无向量。 */
    public String itemEmbeddingText(long itemId) {
        List<String> rows = derived.query("SELECT embedding::text FROM item_embedding WHERE item_id=?",
                (rs, n) -> rs.getString(1), itemId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------------- 报表(ad_event 聚合)----------------

    /**
     * 某广告主下各广告的曝光/点击/转化/花费聚合。
     *
     * <p>分库后 {@code ad}(分片)与 {@code ad_event}(单表 ds_0)的 JOIN 会跨分片边界、需 SQL federation,
     * 故拆成两步各自在分片内执行:① 取该广告主的广告(按 advertiser_id 过滤 → ad 非分片键 → 广播合并);
     * ② {@code ad_event} 单表按 ad_id 聚合。无事件的广告也返回(计数 0)。
     */
    public List<AdReportRow> reportByAdvertiser(long advertiserId) {
        // ① 广告(保序)
        Map<Long, String> ads = new LinkedHashMap<>();
        jdbc.query("SELECT ad_id, title FROM ad WHERE advertiser_id=? ORDER BY ad_id",
                (java.sql.ResultSet rs) -> { ads.put(rs.getLong("ad_id"), rs.getString("title")); },
                advertiserId);
        if (ads.isEmpty()) {
            return List.of();
        }
        // ② ad_event 聚合经 seam(db 直读 / grpc 调 ad-serving);#3 拆库后 advertiser 不再直读 ad_event
        Map<Long, AdReportReader.Stats> stats = reportReader.statsByAds(ads.keySet());
        // ③ 装配(无事件广告补 0)
        List<AdReportRow> out = new ArrayList<>(ads.size());
        for (Map.Entry<Long, String> e : ads.entrySet()) {
            long adId = e.getKey();
            AdReportReader.Stats s = stats.getOrDefault(adId, new AdReportReader.Stats(0, 0, 0, 0));
            long impr = s.impressions(), clk = s.clicks(), conv = s.conversions();
            double spend = s.spend();
            double ctr = impr > 0 ? (double) clk / impr : 0;
            double cvr = clk > 0 ? (double) conv / clk : 0;
            double ecpm = impr > 0 ? spend / impr * 1000 : 0;
            out.add(new AdReportRow(adId, e.getValue(), impr, clk, conv,
                    round2(spend), round4(ctr), round4(cvr), round2(ecpm)));
        }
        return out;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    /** 广告库内行(含 has_emb 标志),service 据此装配 {@link AdView} 并做 reindex 决策。 */
    public record AdRow(long adId, long advertiserId, long itemId, String title, String landingUrl,
                        double qualityScore, String status, String reviewStatus,
                        String optimizationType, Double targetCpa, Long audienceId,
                        boolean hasEmbedding) {
    }
}
