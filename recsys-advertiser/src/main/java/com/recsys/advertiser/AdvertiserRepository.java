package com.recsys.advertiser;

import com.recsys.advertiser.dto.AdReportRow;
import com.recsys.advertiser.dto.AdView;
import com.recsys.advertiser.dto.AdvertiserView;
import com.recsys.advertiser.dto.BidwordView;
import com.recsys.advertiser.dto.CreativeView;
import com.recsys.advertiser.persistence.mapper.primary.AdvertiserMapper;
import com.recsys.advertiser.persistence.mapper.derived.AdvertiserItemEmbeddingMapper;
import com.recsys.advertiser.persistence.model.GeneratedId;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 广告主侧真源读写(Mapper/XML):advertiser / ad / ad_creative / bidword / ad_embedding。
 *
 * <p>只负责库内一致性;Redis 倒排与向量的在线同步在 {@link AdvertiserService} 编排
 * (借 {@link AdEmbeddingRepository} 等),因此本类不感知 Redis。
 * <p>主键全部由 <b>ShardingSphere 的 Snowflake keyGenerator</b> 生成(见 sharding.yaml):insert 不带主键列,
 * ShardingSphere 注入 8 字节、时间有序、去中心化的 id,并按分片键路由到 ds_0 / ds_1;生成值经
 * {@code Statement.getGeneratedKeys()}({@code useGeneratedKeys})回传。advertiser 按 advertiser_id 分库,
 * ad 与 advertiser 按 advertiser_id 同库；bidword/ad_creative 按 ad_id 分库。这替代了单库 IDENTITY——分库后跨库自增会冲突。
 */
@Repository
public class AdvertiserRepository {

    private final AdvertiserMapper mapper;
    private final AdvertiserItemEmbeddingMapper embeddings;
    private final AdReportReader reportReader;

    /** 数据路由由Mapper绑定负责，业务编排继续保持原报告来源。 */
    public AdvertiserRepository(AdvertiserMapper mapper, AdvertiserItemEmbeddingMapper embeddings,
                                AdReportReader reportReader) {
        this.mapper = mapper;
        this.embeddings = embeddings;
        this.reportReader = reportReader;
    }

    // ---------------- advertiser ----------------

    /** 插入广告主,advertiser_id 由 ShardingSphere Snowflake 生成、getGeneratedKeys 回传。 */
    public long insertAdvertiser(String name, double dailyBudget, String status) {
        GeneratedId key = new GeneratedId();
        mapper.insertAdvertiser(key, name, dailyBudget, status);
        return key.valueOrZero();
    }

    /** 部分更新:仅非 null 字段写库(COALESCE 保留原值)。返回受影响行数。 */
    public int updateAdvertiser(long id, String name, Double dailyBudget, String status) {
        return mapper.updateAdvertiser(id, name, dailyBudget, status);
    }

    /** 存在性检查仍走广告主分片，供服务校验归属对象。 */
    public boolean advertiserExists(long id) {
        return mapper.countAdvertiser(id) > 0;
    }

    /** 不含预算消耗(spentToday/remaining 由 service 从 Redis 补)。 */
    public AdvertiserView findAdvertiserRaw(long id) {
        var rows = mapper.findAdvertiser(id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按广告主ID稳定排序，消耗预算仍由服务补充。 */
    public List<AdvertiserView> listAdvertisersRaw() {
        return mapper.listAdvertisers();
    }

    // ---------------- ad ----------------

    /** 插入广告,ad_id 由 ShardingSphere Snowflake 生成、getGeneratedKeys 回传(分库键=advertiser_id)。 */
    public long insertAd(long advertiserId, long itemId, String title, String landingUrl,
                         double qualityScore, String status, String reviewStatus,
                         String optimizationType, Double targetCpa, Long audienceId) {
        GeneratedId key = new GeneratedId();
        mapper.insertAd(key, advertiserId, itemId, title, landingUrl, qualityScore, status, reviewStatus, optimizationType, targetCpa, audienceId);
        return key.valueOrZero();
    }

    /** 新广告生成ID后回填默认落地地址。 */
    public void setAdLandingUrl(long adId, String url) {
        mapper.setAdLandingUrl(adId, url);
    }

    /** 部分更新。targetCpa=null表示不改，audienceId=null则显式清空定向。 */
    public int updateAd(long adId, Long itemId, String title, String landingUrl, Double qualityScore,
                        String status, String optimizationType, Double targetCpa, Long audienceId) {
        return mapper.updateAd(adId, itemId, title, landingUrl, qualityScore, status, optimizationType, targetCpa, audienceId);
    }

    /** 保存服务层已经校验的启停状态，返回实际更新数。 */
    public int setAdStatus(long adId, String status) {
        return mapper.setAdStatus(adId, status);
    }

    /** 区分不存在广告与后续无子资源的广告。 */
    public boolean adExists(long adId) {
        return mapper.countAd(adId) > 0;
    }

    /** 查询归属供服务鉴权；缺失返回null。 */
    public Long advertiserOfAd(long adId) {
        var rows = mapper.advertiserOfAd(adId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 广告基础信息(不含子资源),供 service 装配视图 / reindex 取 itemId、status。
     * <p>分库后 {@code ad}(分片)与 {@code ad_embedding}(单表 ds_0)不能跨边界 JOIN —— 先按 ad_id 取广告
     * (分片内),再单查 ad_embedding 补 hasEmbedding 标志。
     */
    public AdRow findAdRow(long adId) {
        List<AdRow> rows = mapper.findAd(adId);
        return rows.isEmpty() ? null : withEmbedding(rows.get(0), embeddingExists(adId));
    }

    /** 按广告主读取广告，批量补充向量存在标志。 */
    public List<AdRow> listAdRows(long advertiserId) {
        List<AdRow> ads = mapper.listAds(advertiserId);
        if (ads.isEmpty()) return ads;
        // 广告向量单表与分片广告分开访问，继续一次批量读取避免N+1。
        Long[] ids = ads.stream().map(AdRow::adId).toArray(Long[]::new);
        var withEmb = new java.util.HashSet<>(mapper.embeddingAdIds(ids));
        return ads.stream().map(r -> withEmbedding(r, withEmb.contains(r.adId()))).toList();
    }

    private static AdRow withEmbedding(AdRow r, boolean hasEmbedding) {
        return new AdRow(r.adId(), r.advertiserId(), r.itemId(), r.title(), r.landingUrl(),
                r.qualityScore(), r.status(), r.reviewStatus(), r.optimizationType(), r.targetCpa(),
                r.audienceId(), hasEmbedding, r.reviewReason());
    }

    /** 审核决定落库(A2):设 review_status + review_reason。 */
    public int setAdReview(long adId, String reviewStatus, String reason) {
        return mapper.setAdReview(adId, reviewStatus, reason);
    }

    private boolean embeddingExists(long adId) {
        return mapper.countEmbedding(adId) > 0;
    }

    /** 删除广告及其子资源(creative/bidword/embedding/事件保留作审计)。Redis 倒排清理在 service 层。 */
    public void deleteAd(long adId) {
        mapper.deleteAdBidwords(adId);
        mapper.deleteAdCreatives(adId);
        mapper.deleteEmbedding(adId);
        mapper.deleteAd(adId);
    }

    /** 校验关联内容是否存在，不引入跨分片关联查询。 */
    public boolean itemExists(long itemId) {
        return mapper.countItem(itemId) > 0;
    }

    // ---------------- bidword ----------------

    /** 新增竞价词并回填分片驱动生成的ID。 */
    public long insertBidword(long adId, String keyword, String matchType, double bid, String bidMode) {
        GeneratedId key = new GeneratedId();
        mapper.insertBidword(key, adId, keyword, matchType, bid, bidMode);
        return key.valueOrZero();
    }

    /** 只更新非null字段，保留原出价模式和匹配规则。 */
    public int updateBidword(long id, String keyword, String matchType, Double bid, String bidMode) {
        return mapper.updateBidword(id, keyword, matchType, bid, bidMode);
    }

    /** 删除单个词，调用者依据影响行数判断是否存在。 */
    public int deleteBidword(long id) {
        return mapper.deleteBidword(id);
    }

    /** 读取词及广告归属供更新前校验；缺失返回null。 */
    public BidwordView findBidword(long id) {
        var rows = mapper.findBidword(id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按ID保序读取子资源，供目录事件和视图组装。 */
    public List<BidwordView> listBidwords(long adId) {
        return mapper.listBidwords(adId);
    }

    // ---------------- ad_creative ----------------

    /** 新增创意并回填分片驱动生成的ID。 */
    public long insertCreative(long adId, String title, String landingUrl, String status) {
        GeneratedId key = new GeneratedId();
        mapper.insertCreative(key, adId, title, landingUrl, status);
        return key.valueOrZero();
    }

    /** 仅修改非null创意字段，返回实际更新数。 */
    public int updateCreative(long creativeId, String title, String landingUrl, String status) {
        return mapper.updateCreative(creativeId, title, landingUrl, status);
    }

    /** 删除单创意，调用者依据影响行数处理缺失。 */
    public int deleteCreative(long creativeId) {
        return mapper.deleteCreative(creativeId);
    }

    /** 查询创意归属广告，供服务层进行权限校验。 */
    public Long adOfCreative(long creativeId) {
        var rows = mapper.adOfCreative(creativeId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按创意ID稳定排序，供目录事件和视图使用。 */
    public List<CreativeView> listCreatives(long adId) {
        return mapper.listCreatives(adId);
    }

    // ---------------- ad_embedding(在线语义召回依赖)----------------

    /** 从 item_embedding 拷贝该广告关联 item 的向量(item 无向量则 0 行)。upsert 语义。
     *  #3 拆库:item_embedding(派生库)与 ad_embedding(分片库)可能不同库,拆读(derived,text 传输)+写(primary Mapper)。 */
    public int copyEmbeddingFromItem(long adId, long itemId) {
        var rows = embeddings.find(itemId);
        if (rows.isEmpty() || rows.get(0) == null || rows.get(0).embedding() == null) return 0;
        var row = rows.get(0);
        return mapper.upsertEmbedding(adId, row.embedding(), row.model());
    }

    /** 删除向量投影，广告及其其他子资源由对应用例管理。 */
    public void deleteEmbedding(long adId) {
        mapper.deleteEmbedding(adId);
    }

    /** #3:读 item 向量(pgvector ::text 形式),供广告目录事件携带(拆库后 ad-serving 消费端写自有 ad_embedding)。null=无向量。 */
    public String itemEmbeddingText(long itemId) {
        List<String> rows = embeddings.text(itemId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------------- 报表(ad_event 聚合)----------------

    /**
     * 某广告主下各广告的曝光/点击/转化/花费聚合。
     *
     * <p>分库后 {@code ad}(分片)与 {@code ad_event}(单表 ds_0)的 JOIN 会跨分片边界、需 SQL federation,
     * 故拆成两步各自在分片内执行:① 取该广告主的广告(按 advertiser_id 过滤 → 定位广告主所在分片);
     * ② {@code ad_event} 单表按 ad_id 聚合。无事件的广告也返回(计数 0)。
     */
    public List<AdReportRow> reportByAdvertiser(long advertiserId) {
        // ① 广告(保序)
        Map<Long, String> ads = new LinkedHashMap<>();
        mapper.adTitles(advertiserId).forEach(row -> ads.put(row.adId(), row.title()));
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
                        boolean hasEmbedding, String reviewReason) {
    }
}
