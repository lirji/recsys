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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    // 小线程池:让"取广告(召回+竞价+频控)"与"取自然结果"重叠执行,而非串行。
    // 广告链路失败已 fail-soft 返回空,不影响自然流;队列满/异常退纯自然流。
    private final ExecutorService adPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "feed-ads");
        t.setDaemon(true);
        return t;
    });

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

        // 1+2. 自然结果与广告是两条相互独立的重链路 → 并行。广告(召回+竞价+频控)提交到线程池,
        //       自然结果在当前线程算,二者重叠;feed 端到端 ≈ max(自然, 广告) 而非二者之和。
        boolean adEnabled = query != null && !query.isBlank() && adProps.getAdLoad().isEnabled();
        CompletableFuture<AdResult> adFuture = adEnabled
                ? CompletableFuture.supplyAsync(() -> loadAds(userId, query, sc), adPool)
                : CompletableFuture.completedFuture(AdResult.EMPTY);

        RecommendResponse organic =
                recommendOrchestrator.recommend(new RecommendRequest(userId, size, sc, query));
        int target = size > 0 ? size : organic.items().size();

        // 3. 汇合广告结果(loadAds 内部已 fail-soft;join 再兜一层防线程池拒绝/中断)
        AdResult adResult = AdResult.EMPTY;
        try {
            adResult = adFuture.join();
        } catch (Exception e) {
            log.debug("混排取广告失败,退纯自然流 user={}: {}", userId, e.getMessage());
        }
        String requestId = adResult.requestId();
        List<SponsoredAd> ads = adResult.ads();

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

    /** 取竞得广告 + 频控过滤,内部 fail-soft(异常退空广告),供并行任务调用。 */
    private AdResult loadAds(long userId, String query, String scene) {
        try {
            SearchAdsResponse adsResp =
                    searchAdsOrchestrator.searchAds(userId, query, adProps.getAdLoad().getMaxAds(), scene);
            List<SponsoredAd> filtered = frequencyCap.filter(userId, adsResp.ads());   // 频控过滤
            return new AdResult(adsResp.requestId(), filtered);
        } catch (Exception e) {
            log.debug("混排取广告失败,退纯自然流 user={}: {}", userId, e.getMessage());
            return AdResult.EMPTY;
        }
    }

    /** 并行广告任务的结果:请求 ID + 频控后广告。 */
    private record AdResult(String requestId, List<SponsoredAd> ads) {
        static final AdResult EMPTY = new AdResult(null, List.of());
    }
}
