package com.recsys.advertiser;

import com.recsys.advertiser.catalog.CatalogWriteRouter;
import com.recsys.advertiser.catalog.CatalogMutation;
import com.recsys.advertiser.catalog.CatalogProtocol.Kind;

import com.recsys.advertiser.dto.CreativeUpsert;
import com.recsys.advertiser.dto.CreativeView;
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

/**
 * 广告创意管理(DCO,docs/05 §7 M7:一个广告多套创意,在线多臂老虎机按创意级 CTR 择优)。
 * 创意只决定展示、不进倒排/向量,故无在线召回同步。
 */
@RestController
@PreAuthorize("hasAnyRole('ADVERTISER','ADMIN')")   // P0 纵深防御
public class CreativeController {

    private final AdvertiserService service;
    private CatalogWriteRouter catalog;

    /** 默认关闭时不装配可靠路由；可靠模式必须经过原Controller鉴权和DTO校验。 */
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setCatalogRouter(CatalogWriteRouter catalog) { this.catalog=catalog; }


    public CreativeController(AdvertiserService service) {
        this.service = service;
    }

    @PostMapping("/api/advertiser/ad/{adId}/creative")
    public CreativeView add(@PathVariable long adId, @Valid @RequestBody CreativeUpsert req) {
        if(catalog!=null)return catalog.execute(new CatalogMutation(Kind.CREATIVE_CREATE,adId,null,null,req,null,null,null,null),null,CreativeView.class);
        return service.addCreative(adId, req);
    }

    @GetMapping("/api/advertiser/ad/{adId}/creative")
    public List<CreativeView> list(@PathVariable long adId) {
        return service.listCreatives(adId);
    }

    @PutMapping("/api/advertiser/creative/{id}")
    public CreativeView update(@PathVariable long id, @Valid @RequestBody CreativeUpsert req) {
        if(catalog!=null)return catalog.execute(new CatalogMutation(Kind.CREATIVE_UPDATE,id,null,null,req,null,null,null,null),null,CreativeView.class);
        return service.updateCreative(id, req);
    }

    @DeleteMapping("/api/advertiser/creative/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long id) {
        if(catalog!=null)catalog.execute(new CatalogMutation(Kind.CREATIVE_DELETE,id,null,null,null,null,null,null,null),null,Object.class);
        else service.deleteCreative(id);
        return ResponseEntity.ok(Map.of("ok", true, "creativeId", id));
    }
}
