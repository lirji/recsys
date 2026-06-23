package com.recsys.advertiser;

import com.recsys.advertiser.dto.AdUpsert;
import com.recsys.advertiser.dto.AdView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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
public class AdController {

    private final AdvertiserService service;

    public AdController(AdvertiserService service) {
        this.service = service;
    }

    @PostMapping("/api/advertiser/{advertiserId}/ad")
    public AdView create(@PathVariable long advertiserId, @RequestBody AdUpsert req) {
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
    public AdView update(@PathVariable long adId, @RequestBody AdUpsert req) {
        return service.updateAd(adId, req);
    }

    /** 上/下线广告:?status=active|paused。 */
    @PutMapping("/api/advertiser/ad/{adId}/status")
    public AdView setStatus(@PathVariable long adId, @RequestParam String status) {
        return service.setAdStatus(adId, status);
    }

    @DeleteMapping("/api/advertiser/ad/{adId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long adId) {
        service.deleteAd(adId);
        return ResponseEntity.ok(Map.of("ok", true, "adId", adId));
    }
}
