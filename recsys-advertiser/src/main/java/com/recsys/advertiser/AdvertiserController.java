package com.recsys.advertiser;

import com.recsys.advertiser.catalog.CatalogWriteRouter;
import com.recsys.advertiser.catalog.CatalogMutation;
import com.recsys.advertiser.catalog.CatalogProtocol.Kind;

import com.recsys.advertiser.dto.AdReportRow;
import com.recsys.advertiser.dto.AdvertiserUpsert;
import com.recsys.advertiser.dto.AdvertiserView;
import com.recsys.advertiser.dto.AdvertiserPermissions;
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
    private CatalogWriteRouter catalog;

    /** 默认关闭时不装配可靠路由；可靠模式必须经过原Controller鉴权和DTO校验。 */
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setCatalogRouter(CatalogWriteRouter catalog) { this.catalog=catalog; }


    public AdvertiserController(AdvertiserService service) {
        this.service = service;
    }

    @PostMapping
    public AdvertiserView create(@Valid @RequestBody AdvertiserUpsert req) {
        if(catalog!=null)return catalog.execute(new CatalogMutation(Kind.ROOT_CREATE,0,null,null,null,req,null,null,null),null,AdvertiserView.class);
        return service.createAdvertiser(req);
    }

    @GetMapping
    public List<AdvertiserView> list() {
        return service.listAdvertisers();
    }

    /** 平台操作权限仅用于界面适配，不能代替写接口的服务端判权。 */
    @GetMapping("/permissions")
    public AdvertiserPermissions platformPermissions() {
        return service.platformPermissions();
    }

    /** 返回当前主体对当前广告主的有效权限，拒绝和依赖故障保持不同语义。 */
    @GetMapping("/{id}/permissions")
    public AdvertiserPermissions permissions(@PathVariable long id) {
        return service.advertiserPermissions(id);
    }

    @GetMapping("/{id}")
    public AdvertiserView get(@PathVariable long id) {
        return service.getAdvertiser(id);
    }

    @PutMapping("/{id}")
    public AdvertiserView update(@PathVariable long id, @Valid @RequestBody AdvertiserUpsert req) {
        if(catalog!=null)return catalog.execute(new CatalogMutation(Kind.ROOT_UPDATE,id,null,null,null,req,null,null,null),id,AdvertiserView.class);
        return service.updateAdvertiser(id, req);
    }

    /** 该广告主下各广告的曝光/点击/转化/花费报表(从 ad_event 聚合)。 */
    @GetMapping("/{id}/report")
    public List<AdReportRow> report(@PathVariable long id) {
        return service.report(id);
    }
}
