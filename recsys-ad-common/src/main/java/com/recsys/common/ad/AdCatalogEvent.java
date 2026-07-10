package com.recsys.common.ad;

import java.util.List;

/**
 * 广告目录领域事件(DDD Published Language,P1b 广告目录事件化)。
 *
 * <p><b>快照式(snapshot-per-ad)</b>:任一广告的可服务状态/目录数据变化时,广告主(写侧 {@code recsys-advertiser})
 * 发布该广告<b>当前完整可服务快照</b>;广告在线服务({@code recsys-ad-serving})消费后<b>幂等 upsert</b> 进自有
 * 可服务副本 {@code ad_servable}。这样 ad-serving 不再直读广告主的分片目录库(打破跨上下文 DB 耦合,DB-per-service)。
 *
 * <p>为什么用快照而非增量 delta:快照天然幂等 + 可重放,消费端无需处理乱序/丢失;以 {@code adId} 作 Kafka 消息 key,
 * 同一广告的事件落同一分区、有序消费,last-write-wins 即正确。{@code servable=false} 为墓碑(删广告/下线/未过审)。
 *
 * @param adId             广告 ID(Kafka 消息 key)
 * @param servable         是否可服务(false=墓碑:从副本移除 / 标记不可服务)
 * @param advertiserId     广告主 ID
 * @param itemId           关联物品 ID
 * @param title            广告标题(默认创意)
 * @param landingUrl       落地页
 * @param qualityScore     基础质量度(在线 ad:quality 精细化质量度仍另叠加)
 * @param status           广告状态(active/paused/…)
 * @param reviewStatus     审核状态(approved/pending_review/rejected)
 * @param optimizationType 出价优化方式(CPC/OCPC/…),供 oCPC 参数读取
 * @param targetCpa        目标 CPA(可空)
 * @param audienceId       定向人群包 id(A3,可空=不定向)
 * @param bidwords         竞价词(供倒排重建 / 关键词召回)
 * @param creatives        创意(供 DCO)
 * @param ts               事件时间(epoch millis,仅审计;排序由 Kafka 分区有序保证)
 */
public record AdCatalogEvent(long adId,
                             boolean servable,
                             long advertiserId,
                             long itemId,
                             String title,
                             String landingUrl,
                             double qualityScore,
                             String status,
                             String reviewStatus,
                             String optimizationType,
                             Double targetCpa,
                             Long audienceId,
                             List<Bidword> bidwords,
                             List<Creative> creatives,
                             long ts) {

    /** Kafka 主题:广告目录事件。 */
    public static final String TOPIC = "ad-catalog-events";

    public AdCatalogEvent {
        bidwords = bidwords == null ? List.of() : bidwords;
        creatives = creatives == null ? List.of() : creatives;
    }

    /** 墓碑事件:广告不可服务(删除/下线/未过审),消费端据此从可服务副本移除。 */
    public static AdCatalogEvent tombstone(long adId, long ts) {
        return new AdCatalogEvent(adId, false, 0, 0, null, null, 0,
                null, null, null, null, null, List.of(), List.of(), ts);
    }

    public record Bidword(long id, String keyword, String matchType, double bid) {
    }

    public record Creative(long creativeId, String title, String landingUrl, String status) {
    }
}
