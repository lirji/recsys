package com.recsys.advertiser;

import java.util.Collection;
import java.util.Map;

/**
 * 广告主报表的 {@code ad_event} 聚合来源(#3 ad-serving 上下文物理拆库)。把"按 ad_id 聚合曝光/点击/转化/花费"
 * 与来源解耦,使 advertiser 报表在 ad-serving 拆库后不再跨上下文直读 ad-serving 的 {@code ad_event}。
 *
 * <p>两种来源,用 {@code recsys.ad.report.source} 一键切换/回滚(同 {@code AdCatalogReader} 的
 * {@code @ConditionalOnProperty} 范式):
 * <ul>
 *   <li>{@code db}(默认,{@code matchIfMissing}):{@code DbAdReportReader} 直读 {@code ad_event}(拆库前行为,回滚落点)。</li>
 *   <li>{@code grpc}:{@code GrpcAdReportReader} 经 gRPC 调 ad-serving 的 {@code GetAdEventStats}——
 *       ad-serving 拥有 {@code ad_event}、对外发布报表聚合(Customer-Supplier)。</li>
 * </ul>
 * 聚合口径两侧逐字一致(故 db 与 grpc 产出相同)。
 */
public interface AdReportReader {

    /** 按 ad_id 聚合曝光/点击/转化/花费;无事件的 ad 不在结果里(调用方补 0)。 */
    Map<Long, Stats> statsByAds(Collection<Long> adIds);

    record Stats(long impressions, long clicks, long conversions, double spend) {
    }
}
