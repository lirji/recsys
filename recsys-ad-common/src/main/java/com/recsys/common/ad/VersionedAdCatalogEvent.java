package com.recsys.common.ad;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 完整权威目录快照；版本而非到达顺序决定新旧，UNKNOWN向量不表示权威删除。 */
public record VersionedAdCatalogEvent(
        int schemaVersion, CatalogEventType eventType, UUID sourceId, int ownerShard, String topologyVersion,
        String eventId, long adId, long advertiserId, long aggregateVersion, long advertiserVersion,
        UUID commandId, Instant occurredAt, Instant capturedAt, Lifecycle lifecycle, boolean servable,
        Ad ad, String advertiserStatus, List<Bidword> bidwords, List<Creative> creatives,
        Embedding embedding, boolean catalogComplete, String deletionReason) {

    public static final int SCHEMA_VERSION=1;
    public static final String TOPIC="ad-catalog-v1";
    public static final String TOPOLOGY="advertiser-mod2-child-ad-mod2-v1";
    public enum CatalogEventType { AD_SNAPSHOT, AD_DELETED }
    public enum Lifecycle { PRESENT, DELETED }
    public enum EmbeddingState { PRESENT, UNKNOWN, NOT_APPLICABLE }
    public enum EmbeddingReason { NONE, UNAVAILABLE, MISSING, INVALID, DELETED }

    public VersionedAdCatalogEvent {
        bidwords=bidwords==null?null:List.copyOf(bidwords);
        creatives=creatives==null?null:List.copyOf(creatives);
    }

    /** 来源ad的权威字段；不包含其他owner的资金账户、缓存或向量投影状态。 */
    public record Ad(long itemId,String title,String landingUrl,double qualityScore,String status,
                     String reviewStatus,String reviewReason,String optimizationType,Double targetCpa,Long audienceId) { }
    /** 完整词快照包含原出价模式，不沿用旧事件遗漏字段的结构。 */
    public record Bidword(long id,String keyword,String matchType,double bid,String bidMode) { }
    /** 原reviewStatus必须保留，包括pending和未知值；不能在投影时自动视为approved。 */
    public record Creative(long creativeId,String title,String landingUrl,String status,String reviewStatus) { }
    /** 与冻结item绑定的独立enrichment；重放读取原事件，不重新查询向量。 */
    public record Embedding(EmbeddingState state,Long itemId,String model,List<Double> vector,
                            Instant observedAt,EmbeddingReason reason) {
        public Embedding { vector=vector==null?null:List.copyOf(vector); }
    }
}
