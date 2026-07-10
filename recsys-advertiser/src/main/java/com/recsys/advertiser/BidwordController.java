package com.recsys.advertiser;

import com.recsys.advertiser.dto.BidwordUpsert;
import com.recsys.advertiser.dto.BidwordView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 竞价词管理。增删改即时同步在线竞价词倒排 {@code bidword:inv:{keyword}}。 */
@RestController
@PreAuthorize("hasAnyRole('ADVERTISER','ADMIN')")   // P0 纵深防御
public class BidwordController {

    private final AdvertiserService service;

    public BidwordController(AdvertiserService service) {
        this.service = service;
    }

    @PostMapping("/api/advertiser/ad/{adId}/bidword")
    public BidwordView add(@PathVariable long adId, @Valid @RequestBody BidwordUpsert req) {
        return service.addBidword(adId, req);
    }

    @GetMapping("/api/advertiser/ad/{adId}/bidword")
    public List<BidwordView> list(@PathVariable long adId) {
        return service.listBidwords(adId);
    }

    @PutMapping("/api/advertiser/bidword/{id}")
    public BidwordView update(@PathVariable long id, @Valid @RequestBody BidwordUpsert req) {
        return service.updateBidword(id, req);
    }

    @DeleteMapping("/api/advertiser/bidword/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long id) {
        service.deleteBidword(id);
        return ResponseEntity.ok(Map.of("ok", true, "bidwordId", id));
    }
}
