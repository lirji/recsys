package com.recsys.recengine;

import com.recsys.ad.AdMixer;
import com.recsys.ad.AdProperties;
import com.recsys.ad.FrequencyCapService;
import com.recsys.common.ad.BlendedFeedResponse;
import com.recsys.common.ad.FeedEntry;
import com.recsys.common.ad.SearchAdsResponse;
import com.recsys.common.ad.SponsoredAd;
import com.recsys.common.dto.RecommendRequest;
import com.recsys.common.dto.RecommendResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 混排信息流编排(docs/05 §4.8):自然推荐 + 赞助广告按 Ad Load 规则混排。
 *
 * <p>流程:query 驱动取自然结果(复用 {@link RecommendOrchestrator})→ 取竞得广告
 * (复用 {@link SearchAdsOrchestrator})→ {@link FrequencyCapService} 频控过滤 →
 * {@link AdMixer} 按位次/密度混排 → 对真正展示的广告记频次 → 返回带"赞助"标记的信息流。
 *
 * <p>广告仅在 query 驱动(搜索)时叠加;无 query / Ad Load 关闭 → 纯自然流。每步优雅降级:
 * 广告链路异常不影响自然结果(SearchAdsOrchestrator 内部已 fail-soft 返回空广告)。
 */
@Service
public class FeedOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(FeedOrchestrator.class);
    private static final String SEARCH_SCENE = "search";

    private final RecommendOrchestrator recommendOrchestrator;
    private final SearchAdsOrchestrator searchAdsOrchestrator;
    private final AdMixer adMixer;
    private final FrequencyCapService frequencyCap;
    private final AdProperties adProps;

    public FeedOrchestrator(RecommendOrchestrator recommendOrchestrator,
                            SearchAdsOrchestrator searchAdsOrchestrator,
                            AdMixer adMixer,
                            FrequencyCapService frequencyCap,
                            AdProperties adProps) {
        this.recommendOrchestrator = recommendOrchestrator;
        this.searchAdsOrchestrator = searchAdsOrchestrator;
        this.adMixer = adMixer;
        this.frequencyCap = frequencyCap;
        this.adProps = adProps;
    }

    public BlendedFeedResponse feed(long userId, String query, int size, String scene) {
        String sc = (scene == null || scene.isBlank()) ? SEARCH_SCENE : scene;
        // 1. 自然结果(有 query 则 query 驱动)
        RecommendResponse organic =
                recommendOrchestrator.recommend(new RecommendRequest(userId, size, sc, query));
        int target = size > 0 ? size : organic.items().size();

        // 2. 广告(仅 query 驱动 + Ad Load 开启)
        String requestId = null;
        List<SponsoredAd> ads = List.of();
        if (query != null && !query.isBlank() && adProps.getAdLoad().isEnabled()) {
            try {
                SearchAdsResponse adsResp =
                        searchAdsOrchestrator.searchAds(userId, query, adProps.getAdLoad().getMaxAds(), sc);
                requestId = adsResp.requestId();
                ads = frequencyCap.filter(userId, adsResp.ads());   // 3. 频控过滤
            } catch (Exception e) {
                log.debug("混排取广告失败,退纯自然流 user={}: {}", userId, e.getMessage());
            }
        }

        // 4. 混排
        List<FeedEntry> entries = adMixer.mix(organic.items(), ads, Math.max(target, organic.items().size()));

        // 5. 对真正展示的广告记频次(只记进入信息流的)
        Set<Long> shownAdIds = entries.stream().filter(FeedEntry::ad)
                .map(FeedEntry::adId).collect(Collectors.toSet());
        if (!shownAdIds.isEmpty()) {
            Map<Long, SponsoredAd> byId = ads.stream()
                    .collect(Collectors.toMap(SponsoredAd::adId, Function.identity(), (a, b) -> a));
            List<SponsoredAd> shown = new ArrayList<>();
            for (Long adId : shownAdIds) {
                SponsoredAd a = byId.get(adId);
                if (a != null) {
                    shown.add(a);
                }
            }
            frequencyCap.record(userId, shown);
        }

        log.debug("混排信息流 user={} q=[{}] 自然={} 广告={} 展示广告={}",
                userId, query, organic.items().size(), ads.size(), shownAdIds.size());
        return new BlendedFeedResponse(userId, query, requestId, entries, organic.traceId());
    }
}
