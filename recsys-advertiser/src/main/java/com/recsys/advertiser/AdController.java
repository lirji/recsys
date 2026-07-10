package com.recsys.advertiser;

import com.recsys.advertiser.dto.AdUpsert;
import com.recsys.advertiser.dto.AdView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 广告管理。全部挂在 {@code /api/advertiser/**} 命名空间下,避免与 rec-engine 的
 * {@code /api/ad/click} / {@code /api/ad/conversion}(在线计费)在网关冲突。
 * 新建/列举按广告主作用域({@code /api/advertiser/{id}/ad});单广告操作走 {@code /api/advertiser/ad/{adId}}。
 * 写操作会同步在线竞价词倒排 + ad_embedding。
 */
@RestController
@PreAuthorize("hasAnyRole('ADVERTISER','ADMIN')")   // P0 纵深防御
public class AdController {

    private final AdvertiserService service;

    public AdController(AdvertiserService service) {
        this.service = service;
    }

    @PostMapping("/api/advertiser/{advertiserId}/ad")
    public AdView create(@PathVariable long advertiserId, @Valid @RequestBody AdUpsert req) {
        return service.createAd(advertiserId, req);
    }

    @GetMapping("/api/advertiser/{advertiserId}/ad")
    public List<AdView> list(@PathVariable long advertiserId) {
        return service.listAds(advertiserId);
    }

    @GetMapping("/api/advertiser/ad/{adId}")
    public AdView get(@PathVariable long adId) {
        return service.getAd(adId);
    }

    @PutMapping("/api/advertiser/ad/{adId}")
    public AdView update(@PathVariable long adId, @Valid @RequestBody AdUpsert req) {
        return service.updateAd(adId, req);
    }

    /** 上/下线广告:?status=active|paused。 */
    @PutMapping("/api/advertiser/ad/{adId}/status")
    public AdView setStatus(@PathVariable long adId, @RequestParam String status) {
        return service.setAdStatus(adId, status);
    }

    /** 人审(A2):?decision=approve|reject[&reason=...]。approved 才进可服务集(倒排),故审核即决定投放。 */
    @PostMapping("/api/advertiser/ad/{adId}/review")
    public AdView review(@PathVariable long adId, @RequestParam String decision,
                         @RequestParam(required = false) String reason) {
        return service.reviewAd(adId, decision, reason);
    }

    /** 重新送审(A2):改动创意后重跑机审 → pending/rejected。 */
    @PostMapping("/api/advertiser/ad/{adId}/submit-review")
    public AdView submitReview(@PathVariable long adId) {
        return service.submitForReview(adId);
    }

    @DeleteMapping("/api/advertiser/ad/{adId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long adId) {
        service.deleteAd(adId);
        return ResponseEntity.ok(Map.of("ok", true, "adId", adId));
    }
}
