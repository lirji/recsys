package com.recsys.advertiser.catalog;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.UUID;

/** 稳定owner/开户home查询不暴露原payload、词或actor；权限仍按当前调用者重新核验。 */
@RestController
@PreAuthorize("hasAnyRole('ADVERTISER','ADMIN')")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="recsys.ad.catalog.reliability.enabled",havingValue="true")
public class CatalogCommandController {
    private final CatalogWriteRouter router;
    private final CatalogCommandQuery query;
    public CatalogCommandController(CatalogWriteRouter router,CatalogCommandQuery query){this.router=router;this.query=query;}
    @GetMapping("/api/advertiser/{advertiserId}/catalog-commands/{commandId}")
    public View query(@PathVariable long advertiserId,@PathVariable UUID commandId,
                      @RequestParam(required=false) String afterStep,@RequestParam(required=false) String afterEvent,
                      @RequestParam(defaultValue="100") int limit){return view(router.query(advertiserId,commandId),afterStep,afterEvent,limit);}
    @GetMapping("/api/advertiser/catalog-commands/{commandId}")
    public View queryRoot(@PathVariable UUID commandId,@RequestParam(required=false) String afterStep,
                          @RequestParam(required=false) String afterEvent,@RequestParam(defaultValue="100") int limit){return view(router.query(null,commandId),afterStep,afterEvent,limit);}
    private View view(CatalogProtocol.CommandRow row,String afterStep,String afterEvent,int limit){return new View(row.commandId().toString(),row.advertiserId()==null?null:row.advertiserId().toString(),row.state(),row.phase(),row.resultBytes()!=null,row.errorType(),query.summaries(row.key(),afterStep,afterEvent,limit));}
    public record View(String commandId,String advertiserId,String state,String phase,boolean hasResult,String errorType,CatalogCommandQuery.Summary summary) { }
}
