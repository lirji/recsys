package com.recsys.advertiser.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.recsys.advertiser.dto.BidwordView;
import com.recsys.advertiser.persistence.mapper.primary.CatalogBusinessMapper.*;
import com.recsys.common.ad.VersionedAdCatalogEvent.Embedding;
import java.time.Instant;
import java.util.List;

/** 每个物理步骤的完整冻结结果；事件只能由原片段组合，不能重读当前业务伪造旧快照。 */
public final class CatalogFragments {
    private CatalogFragments() { }
    public record Effect(String table,String operation,long id) { }
    public record Request(CatalogMutation mutation,Long adId) { }
    public record Owner(Root root,Ad before,Ad desired,long advertiserVersion,long version,Instant capturedAt,List<Effect> effects) { }
    public record Children(List<BidwordView> bidwords,List<Creative> creatives,JsonNode response,List<Effect> effects) { }
    public record Frozen(Owner owner,Children children,Embedding embedding,Double spent) { }
    public record Final(JsonNode response,List<Effect> effects) { }
}
