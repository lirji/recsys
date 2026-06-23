package com.recsys.advertiser;

import com.recsys.advertiser.dto.AdReportRow;
import com.recsys.advertiser.dto.AdUpsert;
import com.recsys.advertiser.dto.AdView;
import com.recsys.advertiser.dto.AdvertiserUpsert;
import com.recsys.advertiser.dto.AdvertiserView;
import com.recsys.advertiser.dto.BidwordUpsert;
import com.recsys.advertiser.dto.BidwordView;
import com.recsys.advertiser.dto.CreativeUpsert;
import com.recsys.advertiser.dto.CreativeView;
import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 广告主侧业务编排:库内增删改查(经 {@link AdvertiserRepository})+ 在线存储同步
 * (经 {@link AdIndexSyncService} 维护竞价词倒排;{@code ad_embedding} 在新建/换 item 时从 item_embedding 拷贝)。
 *
 * <p>校验失败抛 400、找不到抛 404({@link ResponseStatusException}),由 Spring 映射 HTTP。
 * 写操作加 {@code @Transactional} 保证库内一致;Redis 同步在事务提交逻辑之后调用(失败不回滚库,
 * 但在线关键词路会回退 DB 按状态过滤,最终一致由下次 reindex 兜底)。
 */
@Service
public class AdvertiserService {

    private static final Logger log = LoggerFactory.getLogger(AdvertiserService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final AdvertiserRepository repo;
    private final AdIndexSyncService sync;
    private final StringRedisTemplate redis;

    public AdvertiserService(AdvertiserRepository repo, AdIndexSyncService sync, StringRedisTemplate redis) {
        this.repo = repo;
        this.sync = sync;
        this.redis = redis;
    }

    // ======================= 广告主 =======================

    @Transactional
    public AdvertiserView createAdvertiser(AdvertiserUpsert req) {
        if (isBlank(req.name())) {
            throw badRequest("name 必填");
        }
        if (req.dailyBudget() == null || req.dailyBudget() < 0) {
            throw badRequest("dailyBudget 必填且非负");
        }
        String status = normalizeAdvertiserStatus(req.status(), "active");
        long id = repo.insertAdvertiser(req.name(), req.dailyBudget(), status);
        log.info("新建广告主 {} ({})", id, req.name());
        return getAdvertiser(id);
    }

    @Transactional
    public AdvertiserView updateAdvertiser(long id, AdvertiserUpsert req) {
        AdvertiserView old = repo.findAdvertiserRaw(id);
        if (old == null) {
            throw notFound("广告主 " + id + " 不存在");
        }
        String newStatus = req.status() == null ? null : normalizeAdvertiserStatus(req.status(), old.status());
        if (req.dailyBudget() != null && req.dailyBudget() < 0) {
            throw badRequest("dailyBudget 非负");
        }
        repo.updateAdvertiser(id, req.name(), req.dailyBudget(), newStatus);
        // 广告主状态变化会改变其全部广告的"可服务"判定 → 重建倒排
        if (newStatus != null && !newStatus.equals(old.status())) {
            sync.reindexAdvertiser(id);
        }
        return getAdvertiser(id);
    }

    public AdvertiserView getAdvertiser(long id) {
        AdvertiserView raw = repo.findAdvertiserRaw(id);
        if (raw == null) {
            throw notFound("广告主 " + id + " 不存在");
        }
        return enrichBudget(raw);
    }

    public List<AdvertiserView> listAdvertisers() {
        return repo.listAdvertisersRaw().stream().map(this::enrichBudget).toList();
    }

    /** 用 Redis 当日已耗预算(与在线 pacing 同源)补全 spentToday / remainingBudget。 */
    private AdvertiserView enrichBudget(AdvertiserView raw) {
        double spent = 0;
        try {
            String v = redis.opsForValue().get(RedisKeys.adBudget(raw.advertiserId(), LocalDate.now().format(DAY)));
            if (v != null) {
                spent = Double.parseDouble(v);
            }
        } catch (Exception e) {
            log.debug("读当日已耗预算失败 adv={}: {}", raw.advertiserId(), e.getMessage());
        }
        double remaining = Math.max(0, raw.dailyBudget() - spent);
        return new AdvertiserView(raw.advertiserId(), raw.name(), raw.dailyBudget(), raw.status(),
                round2(spent), round2(remaining));
    }

    // ======================= 广告 =======================

    @Transactional
    public AdView createAd(long advertiserId, AdUpsert req) {
        if (!repo.advertiserExists(advertiserId)) {
            throw notFound("广告主 " + advertiserId + " 不存在");
        }
        if (req.itemId() == null) {
            throw badRequest("itemId 必填(复用现有 item 的创意/embedding/特征)");
        }
        if (!repo.itemExists(req.itemId())) {
            throw badRequest("item " + req.itemId() + " 不存在");
        }
        String optType = normalizeOptType(req.optimizationType());
        if ("OCPC".equals(optType) && (req.targetCpa() == null || req.targetCpa() <= 0)) {
            throw badRequest("optimizationType=OCPC 时 targetCpa 必填且为正");
        }
        String title = req.title() == null ? "" : req.title();
        double quality = req.qualityScore() == null ? 1.0 : req.qualityScore();
        String status = normalizeAdStatus(req.status(), "active");
        // 主键由 DB IDENTITY 生成回传;落地页未提供时,用生成后的 adId 拼默认值
        long adId = repo.insertAd(advertiserId, req.itemId(), title, req.landingUrl(), quality, status,
                optType, req.targetCpa());
        if (req.landingUrl() == null) {
            repo.setAdLandingUrl(adId, "https://example.com/ad/" + adId);
        }

        // ad_embedding:从关联 item 拷贝(item 无向量则 0 行,SEMANTIC_AD 那路对该广告暂不可用,可降级)
        int emb = repo.copyEmbeddingFromItem(adId, req.itemId());
        if (emb == 0) {
            log.info("广告 {} 关联 item {} 无 embedding,语义召回暂不覆盖该广告", adId, req.itemId());
        }

        if (req.bidwords() != null) {
            for (BidwordUpsert bw : req.bidwords()) {
                insertBidwordRow(adId, bw);
            }
        }
        sync.reindexAd(adId); // 一次性按当前竞价词 + 可服务判定写倒排
        log.info("新建广告 {} (advertiser={}, item={})", adId, advertiserId, req.itemId());
        return getAd(adId);
    }

    @Transactional
    public AdView updateAd(long adId, AdUpsert req) {
        AdvertiserRepository.AdRow old = repo.findAdRow(adId);
        if (old == null) {
            throw notFound("广告 " + adId + " 不存在");
        }
        Long newItem = req.itemId();
        if (newItem != null && !repo.itemExists(newItem)) {
            throw badRequest("item " + newItem + " 不存在");
        }
        String optType = req.optimizationType() == null ? null : normalizeOptType(req.optimizationType());
        String status = req.status() == null ? null : normalizeAdStatus(req.status(), old.status());
        repo.updateAd(adId, newItem, req.title(), req.landingUrl(), req.qualityScore(), status,
                optType, req.targetCpa());
        // 换关联 item → 重拷向量
        if (newItem != null && newItem != old.itemId()) {
            repo.copyEmbeddingFromItem(adId, newItem);
        }
        sync.reindexAd(adId); // 状态 / quality 变化都可能影响倒排
        return getAd(adId);
    }

    /** 上/下线广告(便捷端点)。status ∈ active / paused。 */
    @Transactional
    public AdView setAdStatus(long adId, String status) {
        if (!repo.adExists(adId)) {
            throw notFound("广告 " + adId + " 不存在");
        }
        repo.setAdStatus(adId, normalizeAdStatus(status, "active"));
        sync.reindexAd(adId);
        return getAd(adId);
    }

    @Transactional
    public void deleteAd(long adId) {
        if (!repo.adExists(adId)) {
            throw notFound("广告 " + adId + " 不存在");
        }
        // 删库前取竞价词,删后清倒排(reindexAd 已无法读到这些行)
        List<String> keywords = repo.listBidwords(adId).stream().map(BidwordView::keyword).toList();
        repo.deleteAd(adId);
        sync.removeAd(adId, keywords);
        log.info("删除广告 {}", adId);
    }

    public AdView getAd(long adId) {
        AdvertiserRepository.AdRow row = repo.findAdRow(adId);
        if (row == null) {
            throw notFound("广告 " + adId + " 不存在");
        }
        return assembleAdView(row);
    }

    public List<AdView> listAds(long advertiserId) {
        if (!repo.advertiserExists(advertiserId)) {
            throw notFound("广告主 " + advertiserId + " 不存在");
        }
        return repo.listAdRows(advertiserId).stream().map(this::assembleAdView).toList();
    }

    private AdView assembleAdView(AdvertiserRepository.AdRow r) {
        return new AdView(r.adId(), r.advertiserId(), r.itemId(), r.title(), r.landingUrl(), r.qualityScore(),
                r.status(), r.optimizationType(), r.targetCpa(), r.hasEmbedding(),
                repo.listBidwords(r.adId()), repo.listCreatives(r.adId()));
    }

    // ======================= 竞价词 =======================

    @Transactional
    public BidwordView addBidword(long adId, BidwordUpsert req) {
        if (!repo.adExists(adId)) {
            throw notFound("广告 " + adId + " 不存在");
        }
        BidwordView v = insertBidwordRow(adId, req);
        sync.syncBidword(adId, null, v);
        return v;
    }

    private BidwordView insertBidwordRow(long adId, BidwordUpsert req) {
        if (isBlank(req.keyword())) {
            throw badRequest("keyword 必填");
        }
        if (req.bid() == null || req.bid() <= 0) {
            throw badRequest("bid 必填且为正");
        }
        String matchType = normalizeMatchType(req.matchType());
        String bidMode = req.bidMode() == null ? "CPC" : req.bidMode();
        String kw = req.keyword().trim().toLowerCase();
        long id = repo.insertBidword(adId, kw, matchType, req.bid(), bidMode);
        return new BidwordView(id, adId, kw, matchType, req.bid(), bidMode);
    }

    @Transactional
    public BidwordView updateBidword(long bidwordId, BidwordUpsert req) {
        BidwordView old = repo.findBidword(bidwordId);
        if (old == null) {
            throw notFound("竞价词 " + bidwordId + " 不存在");
        }
        if (req.bid() != null && req.bid() <= 0) {
            throw badRequest("bid 为正");
        }
        String kw = req.keyword() == null ? null : req.keyword().trim().toLowerCase();
        String matchType = req.matchType() == null ? null : normalizeMatchType(req.matchType());
        repo.updateBidword(bidwordId, kw, matchType, req.bid(), req.bidMode());
        BidwordView cur = repo.findBidword(bidwordId);
        sync.syncBidword(old.adId(), old.keyword(), cur);
        return cur;
    }

    @Transactional
    public void deleteBidword(long bidwordId) {
        BidwordView old = repo.findBidword(bidwordId);
        if (old == null) {
            throw notFound("竞价词 " + bidwordId + " 不存在");
        }
        repo.deleteBidword(bidwordId);
        sync.removeBidword(old.adId(), old.keyword());
    }

    public List<BidwordView> listBidwords(long adId) {
        if (!repo.adExists(adId)) {
            throw notFound("广告 " + adId + " 不存在");
        }
        return repo.listBidwords(adId);
    }

    // ======================= 创意(DCO)=======================

    @Transactional
    public CreativeView addCreative(long adId, CreativeUpsert req) {
        if (!repo.adExists(adId)) {
            throw notFound("广告 " + adId + " 不存在");
        }
        if (isBlank(req.title())) {
            throw badRequest("title 必填");
        }
        String status = normalizeAdStatus(req.status(), "active");
        long id = repo.insertCreative(adId, req.title(), req.landingUrl(), status);
        return new CreativeView(id, adId, req.title(), req.landingUrl(), status);
    }

    @Transactional
    public CreativeView updateCreative(long creativeId, CreativeUpsert req) {
        Long adId = repo.adOfCreative(creativeId);
        if (adId == null) {
            throw notFound("创意 " + creativeId + " 不存在");
        }
        String status = req.status() == null ? null : normalizeAdStatus(req.status(), "active");
        repo.updateCreative(creativeId, req.title(), req.landingUrl(), status);
        return repo.listCreatives(adId).stream().filter(c -> c.creativeId() == creativeId)
                .findFirst().orElseThrow(() -> notFound("创意 " + creativeId + " 不存在"));
    }

    @Transactional
    public void deleteCreative(long creativeId) {
        if (repo.deleteCreative(creativeId) == 0) {
            throw notFound("创意 " + creativeId + " 不存在");
        }
    }

    public List<CreativeView> listCreatives(long adId) {
        if (!repo.adExists(adId)) {
            throw notFound("广告 " + adId + " 不存在");
        }
        return repo.listCreatives(adId);
    }

    // ======================= 报表 =======================

    public List<AdReportRow> report(long advertiserId) {
        if (!repo.advertiserExists(advertiserId)) {
            throw notFound("广告主 " + advertiserId + " 不存在");
        }
        return repo.reportByAdvertiser(advertiserId);
    }

    // ======================= 工具 =======================

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /** 管理面允许 active / paused;over_budget 由在线 pacing 自动置位,这里不接受。 */
    private static String normalizeAdvertiserStatus(String s, String def) {
        if (s == null) {
            return def;
        }
        String v = s.trim().toLowerCase();
        if (!v.equals("active") && !v.equals("paused")) {
            throw badRequest("status 仅支持 active / paused");
        }
        return v;
    }

    private static String normalizeAdStatus(String s, String def) {
        if (s == null) {
            return def;
        }
        String v = s.trim().toLowerCase();
        if (!v.equals("active") && !v.equals("paused")) {
            throw badRequest("status 仅支持 active / paused");
        }
        return v;
    }

    private static String normalizeOptType(String s) {
        if (s == null) {
            return "CPC";
        }
        String v = s.trim().toUpperCase();
        if (!v.equals("CPC") && !v.equals("OCPC")) {
            throw badRequest("optimizationType 仅支持 CPC / OCPC");
        }
        return v;
    }

    private static String normalizeMatchType(String s) {
        if (s == null) {
            return "EXACT";
        }
        String v = s.trim().toUpperCase();
        if (!v.equals("EXACT") && !v.equals("PHRASE") && !v.equals("BROAD")) {
            throw badRequest("matchType 仅支持 EXACT / PHRASE / BROAD");
        }
        return v;
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }
}
