package com.recsys.advertiser;

import com.recsys.advertiser.dto.AdReportRow;
import com.recsys.advertiser.dto.AdvertiserUpsert;
import com.recsys.advertiser.dto.AdvertiserView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 广告主管理 + 投放报表(docs/05 §2 recsys-advertiser)。 */
@RestController
@RequestMapping("/api/advertiser")
@PreAuthorize("hasAnyRole('ADVERTISER','ADMIN')")   // P0 纵深防御:写侧管理面需广告主/管理员身份
public class AdvertiserController {

    private final AdvertiserService service;

    public AdvertiserController(AdvertiserService service) {
        this.service = service;
    }

    @PostMapping
    public AdvertiserView create(@Valid @RequestBody AdvertiserUpsert req) {
        return service.createAdvertiser(req);
    }

    @GetMapping
    public List<AdvertiserView> list() {
        return service.listAdvertisers();
    }

    @GetMapping("/{id}")
    public AdvertiserView get(@PathVariable long id) {
        return service.getAdvertiser(id);
    }

    @PutMapping("/{id}")
    public AdvertiserView update(@PathVariable long id, @Valid @RequestBody AdvertiserUpsert req) {
        return service.updateAdvertiser(id, req);
    }

    /** 该广告主下各广告的曝光/点击/转化/花费报表(从 ad_event 聚合)。 */
    @GetMapping("/{id}/report")
    public List<AdReportRow> report(@PathVariable long id) {
        return service.report(id);
    }
}
