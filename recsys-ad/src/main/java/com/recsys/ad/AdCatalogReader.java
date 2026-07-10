package com.recsys.ad;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 广告目录读取抽象(P1b + 收尾):把广告在线所需的<b>全部目录读</b>(标题 / oCPC 参数 / 可服务详情 /
 * 最高出价 / 定向人群)与"从哪读"解耦。ANN(ad_embedding,主库不分片)与 user_embedding 仍在 {@link AdRepository}
 * 内直查——目录读则统一走本接口。
 *
 * <p>两种数据源,由 {@code recsys.ad.catalog.source} 选择、可一键回滚:
 * <ul>
 *   <li>{@code sharded}(默认):{@link ShardedAdCatalogReader} 直读广告主分片目录库(今日行为)。</li>
 *   <li>{@code replica}:{@code ReplicaAdCatalogReader}(recsys-ad-serving)读事件构建的自有可服务副本
 *       {@code ad_servable} —— ad-serving 不再跨上下文直读广告主库(DB-per-service)。</li>
 * </ul>
 * 两侧口径一致:只返回"可服务"广告(广告 active + 审核 approved + 广告主 active);replica 侧因副本只存可服务广告而天然满足。
 */
public interface AdCatalogReader {

    /** 批量取广告标题(竞得后填默认创意标题)。 */
    Map<Long, String> titles(Collection<Long> adIds);

    /** 批量取广告 oCPC 参数(优化方式 + 目标 CPA)。 */
    Map<Long, AdRepository.OcpcParams> ocpcParams(Collection<Long> adIds);

    /** 可服务广告详情(item/advertiser/quality)。{@code adIds=null} 取全部可服务;否则按 id 过滤。 */
    Map<Long, AdDetail> activeAdDetails(Collection<Long> adIds);

    /** 各广告最高出价。{@code adIds=null} 取全部;否则按 id 过滤。 */
    Map<Long, Double> bidMap(Collection<Long> adIds);

    /** 广告的定向人群包 id(A3):未定向的广告缺席。 */
    Map<Long, Long> audiencesByAd(Collection<Long> adIds);

    /** GD(A4):可服务广告的 [itemId, advertiserId](复用 {@link #activeAdDetails})。 */
    default Map<Long, long[]> adItemAdvertiser(Collection<Long> adIds) {
        Map<Long, long[]> out = new HashMap<>();
        activeAdDetails(adIds).forEach((adId, d) -> out.put(adId, new long[]{d.itemId(), d.advertiserId()}));
        return out;
    }

    /** 可服务广告详情(ANN/关键词召回后回填 + GD 用)。 */
    record AdDetail(long itemId, long advertiserId, double quality) {
    }
}
