package com.recsys.advertiser;

import com.recsys.advertiser.authz.AdvertiserAuthz;
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
 *
 * <p>细粒度归属判权({@link AdvertiserAuthz},默认 disabled):所有公开入口<b>先判权后变更</b>——
 * 广告/竞价词/创意的权限继承其 advertiser 作用域;判权触发的内部读走免检私有路径
 * (已在写口判过 edit,view ⊆ edit,免重复判权与写后读窗口)。
 */
@Service
public class AdvertiserService {

    private static final Logger log = LoggerFactory.getLogger(AdvertiserService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final AdvertiserRepository repo;
    private final AdIndexSyncService sync;
    private final StringRedisTemplate redis;
    private final AdvertiserAuthz authz;

    public AdvertiserService(AdvertiserRepository repo, AdIndexSyncService sync, StringRedisTemplate redis,
                             AdvertiserAuthz authz) {
        this.repo = repo;
        this.sync = sync;
        this.redis = redis;
        this.authz = authz;
    }

    // ======================= 广告主 =======================

    @Transactional
    public AdvertiserView createAdvertiser(AdvertiserUpsert req) {
        authz.requirePlatform("administrate");   // 开户是平台职能(enforce 下)
        if (isBlank(req.name())) {
            throw badRequest("name 必填");
        }
        if (req.dailyBudget() == null || req.dailyBudget() < 0) {
            throw badRequest("dailyBudget 必填且非负");
        }
        String status = normalizeAdvertiserStatus(req.status(), "active");
        long id = repo.insertAdvertiser(req.name(), req.dailyBudget(), status);
        authz.onAdvertiserCreated(id);   // 归属双写(owner=创建者);enforce 下失败随事务回滚建号
        log.info("新建广告主 {} ({})", id, req.name());
        return viewAdvertiser(id);
    }

    @Transactional
    public AdvertiserView updateAdvertiser(long id, AdvertiserUpsert req) {
        authz.requireAdvertiser("manage", id);
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
        return viewAdvertiser(id);
    }

    public AdvertiserView getAdvertiser(long id) {
        authz.requireAdvertiser("view", id);
        return viewAdvertiser(id);
    }

    /** 免检读(内部用):创建/更新等已判过权的路径回读详情。 */
    private AdvertiserView viewAdvertiser(long id) {
        AdvertiserView raw = repo.findAdvertiserRaw(id);
        if (raw == null) {
            throw notFound("广告主 " + id + " 不存在");
        }
        return enrichBudget(raw);
    }

    public List<AdvertiserView> listAdvertisers() {
        List<AdvertiserView> all = repo.listAdvertisersRaw().stream().map(this::enrichBudget).toList();
        return authz.filterViewable(all, AdvertiserView::advertiserId);
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
        authz.requireAdvertiser("edit", advertiserId);
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
        // 创意机审(A2):新建广告先过机审 → rejected(违禁)/ pending_review(待人审)。
        // 可服务集只含 approved,故新广告默认<b>不投放</b>,须经 /review 人审通过后才进倒排。
        CreativeReview.Decision review = CreativeReview.machineReview(title, req.landingUrl());
        // 主键由 DB IDENTITY 生成回传;落地页未提供时,用生成后的 adId 拼默认值
        long adId = repo.insertAd(advertiserId, req.itemId(), title, req.landingUrl(), quality, status,
                review.status(), optType, req.targetCpa());
        repo.setAdReview(adId, review.status(), review.reason());
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
        sync.reindexAd(adId); // 一次性按当前竞价词 + 可服务判定写倒排(pending/rejected → 不进倒排)
        log.info("新建广告 {} (advertiser={}, item={}) 机审 → {}", adId, advertiserId, req.itemId(), review.status());
        return viewAd(adId);
    }

    /**
     * 人审决定(A2):approve → approved(进倒排、可投放)、reject → rejected、其余 → pending。
     * 变更 review_status 后 reindex —— approved 才进"可服务集"(倒排),故审核直接决定是否投放。
     */
    @Transactional
    public AdView reviewAd(long adId, String decision, String reason) {
        authz.requirePlatform("review");   // 人审是平台运营/管理员职能,非广告主自审
        AdvertiserRepository.AdRow ad = repo.findAdRow(adId);
        if (ad == null) {
            throw notFound("广告 " + adId + " 不存在");
        }
        String status = CreativeReview.normalizeDecision(decision);
        repo.setAdReview(adId, status, reason);
        sync.reindexAd(adId); // approved → 进倒排;pending/rejected → 移出倒排
        log.info("广告 {} 审核 → {}({})", adId, status, reason);
        return viewAd(adId);
    }

    /** 重新送审(A2):改动创意后重跑机审 → pending/rejected;approved 广告改动应先送审再复核。 */
    @Transactional
    public AdView submitForReview(long adId) {
        AdvertiserRepository.AdRow ad = repo.findAdRow(adId);
        if (ad == null) {
            throw notFound("广告 " + adId + " 不存在");
        }
        authz.requireAdvertiser("edit", ad.advertiserId());
        CreativeReview.Decision d = CreativeReview.machineReview(ad.title(), ad.landingUrl());
        repo.setAdReview(adId, d.status(), d.reason());
        sync.reindexAd(adId);
        return viewAd(adId);
    }

    @Transactional
    public AdView updateAd(long adId, AdUpsert req) {
        AdvertiserRepository.AdRow old = repo.findAdRow(adId);
        if (old == null) {
            throw notFound("广告 " + adId + " 不存在");
        }
        authz.requireAdvertiser("edit", old.advertiserId());
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
        return viewAd(adId);
    }

    /** 上/下线广告(便捷端点)。status ∈ active / paused。 */
    @Transactional
    public AdView setAdStatus(long adId, String status) {
        if (authz.enabled()) {
            authz.requireAdvertiser("edit", advertiserIdOfAd(adId));
        }
        if (!repo.adExists(adId)) {
            throw notFound("广告 " + adId + " 不存在");
        }
        repo.setAdStatus(adId, normalizeAdStatus(status, "active"));
        sync.reindexAd(adId);
        return viewAd(adId);
    }

    @Transactional
    public void deleteAd(long adId) {
        if (authz.enabled()) {
            authz.requireAdvertiser("edit", advertiserIdOfAd(adId));
        }
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
        authz.requireAdvertiser("view", row.advertiserId());
        return assembleAdView(row);
    }

    /** 免检读(内部用):写口已判过 edit(view ⊆ edit)的路径回读广告详情。 */
    private AdView viewAd(long adId) {
        AdvertiserRepository.AdRow row = repo.findAdRow(adId);
        if (row == null) {
            throw notFound("广告 " + adId + " 不存在");
        }
        return assembleAdView(row);
    }

    /** 归属解析:广告 → 其广告主 id(仅判权启用时调用,disabled 零额外查询)。 */
    private long advertiserIdOfAd(long adId) {
        AdvertiserRepository.AdRow row = repo.findAdRow(adId);
        if (row == null) {
            throw notFound("广告 " + adId + " 不存在");
        }
        return row.advertiserId();
    }

    public List<AdView> listAds(long advertiserId) {
        authz.requireAdvertiser("view", advertiserId);
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
        if (authz.enabled()) {
            authz.requireAdvertiser("edit", advertiserIdOfAd(adId));
        }
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
        if (authz.enabled()) {
            authz.requireAdvertiser("edit", advertiserIdOfAd(old.adId()));
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
        if (authz.enabled()) {
            authz.requireAdvertiser("edit", advertiserIdOfAd(old.adId()));
        }
        repo.deleteBidword(bidwordId);
        sync.removeBidword(old.adId(), old.keyword());
    }

    public List<BidwordView> listBidwords(long adId) {
        if (authz.enabled()) {
            authz.requireAdvertiser("view", advertiserIdOfAd(adId));
        }
        if (!repo.adExists(adId)) {
            throw notFound("广告 " + adId + " 不存在");
        }
        return repo.listBidwords(adId);
    }

    // ======================= 创意(DCO)=======================

    @Transactional
    public CreativeView addCreative(long adId, CreativeUpsert req) {
        if (authz.enabled()) {
            authz.requireAdvertiser("edit", advertiserIdOfAd(adId));
        }
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
        if (authz.enabled()) {
            authz.requireAdvertiser("edit", advertiserIdOfAd(adId));
        }
        String status = req.status() == null ? null : normalizeAdStatus(req.status(), "active");
        repo.updateCreative(creativeId, req.title(), req.landingUrl(), status);
        return repo.listCreatives(adId).stream().filter(c -> c.creativeId() == creativeId)
                .findFirst().orElseThrow(() -> notFound("创意 " + creativeId + " 不存在"));
    }

    @Transactional
    public void deleteCreative(long creativeId) {
        if (authz.enabled()) {
            Long adId = repo.adOfCreative(creativeId);
            if (adId == null) {
                throw notFound("创意 " + creativeId + " 不存在");
            }
            authz.requireAdvertiser("edit", advertiserIdOfAd(adId));
        }
        if (repo.deleteCreative(creativeId) == 0) {
            throw notFound("创意 " + creativeId + " 不存在");
        }
    }

    public List<CreativeView> listCreatives(long adId) {
        if (authz.enabled()) {
            authz.requireAdvertiser("view", advertiserIdOfAd(adId));
        }
        if (!repo.adExists(adId)) {
            throw notFound("广告 " + adId + " 不存在");
        }
        return repo.listCreatives(adId);
    }

    // ======================= 报表 =======================

    public List<AdReportRow> report(long advertiserId) {
        authz.requireAdvertiser("view", advertiserId);
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
