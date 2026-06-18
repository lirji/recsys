package com.recsys.recengine;

import com.recsys.common.ad.SearchAdsResponse;
import com.recsys.common.dto.RecommendRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 搜索广告入口(docs/05 §5):
 * <ul>
 *   <li>{@code GET /api/search-ads?q=&userId=&slots=&scene=} —— query 驱动,返回按 eCPM 竞价、
 *       GSP 计费后的赞助广告;与自然推荐 {@code /api/recommend} 并存。</li>
 *   <li>{@code POST /api/ad/click} —— 点击回传,CPC 扣预算 + 落 CLICK。</li>
 *   <li>{@code POST /api/ad/conversion} —— 转化回传(advertiser),落 CONVERSION。</li>
 * </ul>
 * 点击/转化都需带搜索响应里的 {@code requestId} 做计费归因。
 */
@RestController
public class SearchAdsController {

    private final SearchAdsOrchestrator orchestrator;

    public SearchAdsController(SearchAdsOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping("/api/search-ads")
    public SearchAdsResponse searchAds(
            @RequestParam("q") String q,
            @RequestParam(required = false, defaultValue = "0") long userId,
            @RequestParam(required = false, defaultValue = "0") int slots,
            @RequestParam(required = false, defaultValue = RecommendRequest.DEFAULT_SCENE) String scene) {
        return orchestrator.searchAds(userId, q, slots, scene);
    }

    @PostMapping("/api/ad/click")
    public void click(
            @RequestParam("requestId") String requestId,
            @RequestParam("adId") long adId,
            @RequestParam(required = false, defaultValue = "0") long userId) {
        orchestrator.recordClick(requestId, adId, userId);
    }

    @PostMapping("/api/ad/conversion")
    public void conversion(
            @RequestParam("requestId") String requestId,
            @RequestParam("adId") long adId,
            @RequestParam(required = false, defaultValue = "0") long userId) {
        orchestrator.recordConversion(requestId, adId, userId);
    }
}
