package com.recsys.advertiser;

import com.recsys.advertiser.dto.BidwordView;
import com.recsys.advertiser.dto.CreativeView;
import com.recsys.common.ad.AdCatalogEvent;
import com.recsys.common.ad.BidwordInvCodec;
import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 把广告主侧的库内改动同步到在线召回所依赖的派生存储,让新建/暂停/改价的广告对在线链路即时生效。
 *
 * <p>当前覆盖 <b>竞价词倒排</b> {@code bidword:inv:{keyword}}(ZSet,score=出价,member 见
 * {@link BidwordInvCodec})——在线 {@code JdbcAdRecallService} 的关键词路<b>不再回库校验状态</b>,
 * 倒排里有什么就召回什么,因此倒排即"可服务集":只索引 <i>广告 active 且广告主 active</i> 的竞价词。
 * 状态/出价/关键词任一变化都要在这里增量维护,口径与离线 {@code seed-ads} 写倒排完全一致。
 *
 * <p>{@code ad_embedding}(SEMANTIC_AD/U2A 语义召回依赖)由 {@link AdvertiserRepository} 直接 upsert,
 * 不经 Redis,故不在此类。Redis 不可用时所有方法吞异常降级——在线关键词路会自动回退 DB(仍按状态过滤)。
 */
@Service
public class AdIndexSyncService {

    private static final Logger log = LoggerFactory.getLogger(AdIndexSyncService.class);

    private final StringRedisTemplate redis;
    private final AdvertiserRepository repo;
    private final AdCatalogEventPublisher catalogPublisher;

    public AdIndexSyncService(StringRedisTemplate redis, AdvertiserRepository repo,
                              AdCatalogEventPublisher catalogPublisher) {
        this.redis = redis;
        this.repo = repo;
        this.catalogPublisher = catalogPublisher;
    }

    /**
     * 广告当前是否应进倒排(可服务集):广告 active + 审核 approved(A2)+ 广告主 active。
     * over_budget/paused、pending_review/rejected 均视为不可服务 —— 待审/拒审广告不进倒排、不投放。
     */
    public boolean servable(AdvertiserRepository.AdRow ad) {
        if (ad == null || !"active".equalsIgnoreCase(ad.status())) {
            return false;
        }
        if (!"approved".equalsIgnoreCase(ad.reviewStatus())) {
            return false;   // 审核未通过(pending_review / rejected)不进可服务集
        }
        var adv = repo.findAdvertiserRaw(ad.advertiserId());
        return adv != null && "active".equalsIgnoreCase(adv.status());
    }

    /**
     * 按当前库状态重建某广告在倒排里的全部条目:先清掉该广告在各竞价词下的旧 member,
     * 再(可服务时)按当前竞价词 + 出价重新写入。状态切换 / 删广告前调用。
     */
    public void reindexAd(long adId) {
        AdvertiserRepository.AdRow ad = repo.findAdRow(adId);
        if (ad == null) {
            return;
        }
        boolean ok = servable(ad);
        for (BidwordView bw : repo.listBidwords(adId)) {
            removeAdFromKeyword(bw.keyword(), adId);
            if (ok) {
                addMember(bw.keyword(), adId, bw.id(), ad.itemId(), ad.advertiserId(), ad.qualityScore(), bw.bid());
            }
        }
        publishSnapshot(ad, ok);   // P1b:同步发广告目录事件(供 ad-serving 建可服务副本)
    }

    /** 重建某广告主下所有广告(广告主级 暂停/恢复 时调用)。 */
    public void reindexAdvertiser(long advertiserId) {
        for (var ad : repo.listAdRows(advertiserId)) {
            reindexAd(ad.adId());
        }
    }

    /** 新增/改动单个竞价词后维护倒排:从旧关键词移除该广告,再(可服务时)写入新关键词。 */
    public void syncBidword(long adId, String oldKeyword, BidwordView current) {
        AdvertiserRepository.AdRow ad = repo.findAdRow(adId);
        if (ad == null) {
            return;
        }
        if (oldKeyword != null && !oldKeyword.equals(current.keyword())) {
            removeAdFromKeyword(oldKeyword, adId);
        }
        // 同关键词重复 add 等价于更新 score(出价),无需先删
        removeAdFromKeyword(current.keyword(), adId);
        boolean ok = servable(ad);
        if (ok) {
            addMember(current.keyword(), adId, current.id(), ad.itemId(), ad.advertiserId(),
                    ad.qualityScore(), current.bid());
        }
        publishSnapshot(ad, ok);   // P1b
    }

    /** 删除竞价词后,从其关键词倒排移除该广告对应的 member。 */
    public void removeBidword(long adId, String keyword) {
        removeAdFromKeyword(keyword, adId);
        publishSnapshot(adId);     // P1b:竞价词变化,重发快照(广告仍在)
    }

    /** 删广告后,把它从给定关键词集合的倒排里全部抹掉(keywords 取删除前的竞价词)。 */
    public void removeAd(long adId, List<String> keywords) {
        for (String kw : keywords) {
            removeAdFromKeyword(kw, adId);
        }
        catalogPublisher.publishTombstone(adId);   // P1b:广告已删,发墓碑
    }

    // ---------------- P1b:广告目录事件快照构建 ----------------

    /** 读当前库状态构建并发布该广告目录快照;广告已不存在 → 墓碑。 */
    private void publishSnapshot(long adId) {
        AdvertiserRepository.AdRow ad = repo.findAdRow(adId);
        if (ad == null) {
            catalogPublisher.publishTombstone(adId);
            return;
        }
        publishSnapshot(ad, servable(ad));
    }

    /** 用已取的 ad + servable 构建快照(含竞价词/创意),交发布器(开关关则 no-op)。 */
    private void publishSnapshot(AdvertiserRepository.AdRow ad, boolean servable) {
        List<AdCatalogEvent.Bidword> bws = repo.listBidwords(ad.adId()).stream()
                .map(b -> new AdCatalogEvent.Bidword(b.id(), b.keyword(), b.matchType(), b.bid()))
                .toList();
        List<AdCatalogEvent.Creative> crs = repo.listCreatives(ad.adId()).stream()
                .map((CreativeView c) -> new AdCatalogEvent.Creative(
                        c.creativeId(), c.title(), c.landingUrl(), c.status()))
                .toList();
        // #3:携带关联 item 向量(拆库后 ad-serving 消费端写自有 ad_embedding);读失败/无向量则 null。
        String emb = null;
        try {
            emb = repo.itemEmbeddingText(ad.itemId());
        } catch (Exception e) {
            log.warn("读 item_embedding 失败 ad={} item={}: {}", ad.adId(), ad.itemId(), e.getMessage());
        }
        catalogPublisher.publish(new AdCatalogEvent(
                ad.adId(), servable, ad.advertiserId(), ad.itemId(), ad.title(), ad.landingUrl(),
                ad.qualityScore(), ad.status(), ad.reviewStatus(), ad.optimizationType(), ad.targetCpa(),
                ad.audienceId(), bws, crs, emb, System.currentTimeMillis()));
    }

    // ---------------- Redis 原子操作(吞异常降级)----------------

    private void addMember(String keyword, long adId, long bidwordId, long itemId,
                           long advertiserId, double quality, double bid) {
        try {
            String member = BidwordInvCodec.encode(adId, bidwordId, itemId, advertiserId, quality);
            redis.opsForZSet().add(RedisKeys.bidwordInv(keyword), member, bid);
        } catch (Exception e) {
            log.warn("写竞价词倒排失败 kw={} ad={}: {}", keyword, adId, e.getMessage());
        }
    }

    /** 移除某关键词 ZSet 里所有属于该广告的 member(member 以 "{adId}:" 起头)。 */
    private void removeAdFromKeyword(String keyword, long adId) {
        try {
            String key = RedisKeys.bidwordInv(keyword);
            Set<String> members = redis.opsForZSet().range(key, 0, -1);
            if (members == null || members.isEmpty()) {
                return;
            }
            String prefix = adId + ":";
            Object[] toRemove = members.stream().filter(m -> m.startsWith(prefix)).toArray();
            if (toRemove.length > 0) {
                redis.opsForZSet().remove(key, toRemove);
            }
        } catch (Exception e) {
            log.warn("清竞价词倒排失败 kw={} ad={}: {}", keyword, adId, e.getMessage());
        }
    }
}
