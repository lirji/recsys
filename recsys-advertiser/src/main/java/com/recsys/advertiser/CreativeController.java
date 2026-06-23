package com.recsys.advertiser;

import com.recsys.advertiser.dto.CreativeUpsert;
import com.recsys.advertiser.dto.CreativeView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 广告创意管理(DCO,docs/05 §7 M7:一个广告多套创意,在线多臂老虎机按创意级 CTR 择优)。
 * 创意只决定展示、不进倒排/向量,故无在线召回同步。
 */
@RestController
public class CreativeController {

    private final AdvertiserService service;

    public CreativeController(AdvertiserService service) {
        this.service = service;
    }

    @PostMapping("/api/advertiser/ad/{adId}/creative")
    public CreativeView add(@PathVariable long adId, @RequestBody CreativeUpsert req) {
        return service.addCreative(adId, req);
    }

    @GetMapping("/api/advertiser/ad/{adId}/creative")
    public List<CreativeView> list(@PathVariable long adId) {
        return service.listCreatives(adId);
    }

    @PutMapping("/api/advertiser/creative/{id}")
    public CreativeView update(@PathVariable long id, @RequestBody CreativeUpsert req) {
        return service.updateCreative(id, req);
    }

    @DeleteMapping("/api/advertiser/creative/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long id) {
        service.deleteCreative(id);
        return ResponseEntity.ok(Map.of("ok", true, "creativeId", id));
    }
}
