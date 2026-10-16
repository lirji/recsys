package com.recsys.advertiser.catalog;

import com.recsys.common.ad.*;
import com.recsys.advertiser.catalog.CatalogFragments.*;
import com.recsys.advertiser.dto.*;
import java.util.List;

/** 组合两个已冻结物理片段，UNKNOWN enrichment不改变权威生命周期。 */
public final class CatalogSnapshotAssembler {
    /** 墓碑由显式删除命令产生；任何读取失败都不能进入此分支。 */
    public VersionedAdCatalogEvent event(CatalogProtocol.CommandKey key,Owner owner,Children children,
                                         VersionedAdCatalogEvent.Embedding embedding,boolean deleted) {
        var ad=owner.desired();var at=owner.capturedAt();
        return new VersionedAdCatalogEvent(1,deleted?VersionedAdCatalogEvent.CatalogEventType.AD_DELETED:VersionedAdCatalogEvent.CatalogEventType.AD_SNAPSHOT,
                key.source(),(int)(owner.root().advertiserId()%2),CatalogProtocol.TOPOLOGY,
                CatalogEventCodec.eventId(key.source(),ad.adId(),owner.version()),ad.adId(),ad.advertiserId(),owner.version(),owner.advertiserVersion(),
                key.command(),at,at,deleted?VersionedAdCatalogEvent.Lifecycle.DELETED:VersionedAdCatalogEvent.Lifecycle.PRESENT,
                !deleted && CatalogEventCodec.servable(owner.root().status(),ad.status(),ad.reviewStatus()),
                deleted?null:new VersionedAdCatalogEvent.Ad(ad.itemId(),ad.title(),ad.landingUrl(),ad.qualityScore(),ad.status(),ad.reviewStatus(),ad.reviewReason(),ad.optimizationType(),ad.targetCpa(),ad.audienceId()),
                deleted?null:owner.root().status(),deleted?List.of():children.bidwords().stream().map(w->new VersionedAdCatalogEvent.Bidword(w.id(),w.keyword(),w.matchType(),w.bid(),w.bidMode())).toList(),
                deleted?List.of():children.creatives().stream().map(c->new VersionedAdCatalogEvent.Creative(c.creativeId(),c.title(),c.landingUrl(),c.status(),c.reviewStatus())).toList(),
                deleted?new VersionedAdCatalogEvent.Embedding(VersionedAdCatalogEvent.EmbeddingState.NOT_APPLICABLE,null,null,null,at,VersionedAdCatalogEvent.EmbeddingReason.DELETED):embedding,
                true,deleted?"EXPLICIT_DELETE":null);
    }

    /** 写响应使用同一次冻结的业务快照；Kafka/在线投影状态另由命令查询展示。 */
    public AdView view(Frozen frozen) {
        var a=frozen.owner().desired();
        return new AdView(a.adId(),a.advertiserId(),a.itemId(),a.title(),a.landingUrl(),a.qualityScore(),a.status(),a.optimizationType(),a.targetCpa(),a.audienceId(),
                frozen.embedding()!=null && frozen.embedding().state()==VersionedAdCatalogEvent.EmbeddingState.PRESENT,
                frozen.children().bidwords(),frozen.children().creatives().stream().map(c->new CreativeView(c.creativeId(),c.adId(),c.title(),c.landingUrl(),c.status())).toList(),a.reviewStatus(),a.reviewReason());
    }
}
