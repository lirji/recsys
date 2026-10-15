package com.recsys.adserving.report;

import com.recsys.adserving.persistence.mapper.addb.AdReportStatsMapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * 广告主报表所需的 {@code ad_event} 聚合(#3 ad-serving 物理拆库)。ad-serving 拥有 {@code ad_event},对外经
 * gRPC {@code GetAdEventStats} 发布"按 ad_id 聚合曝光/点击/转化/花费";advertiser(Customer)传自有 ad 表的
 * ad_id、只碰 ad_event —— 从而 advertiser 不再跨库直读 ad-serving 的 {@code ad_event}(DB-per-service)。
 *
 * <p>走 {@code adDbJdbc}(ad-serving 自有库,默认 recsys;AD_PG_DB 设则拆库)。聚合口径与原
 * {@code AdvertiserRepository.reportByAdvertiser} 第 ② 步逐字一致(故经 gRPC 与直读产出相同)。
 */
@Repository
public class AdReportStatsRepository {

    private final AdReportStatsMapper mapper;

    /** 报表只经广告自有源查询，保持原读取所有权。 */
    public AdReportStatsRepository(AdReportStatsMapper mapper) {
        this.mapper = mapper;
    }

    /** 跨服务报表传输前的聚合结果行。 */
    public record Stat(long adId, long impressions, long clicks, long conversions, double spend) {
    }

    /** 按 ad_id 聚合 ad_event;无事件的 ad 不在结果里(调用方补 0)。 */
    public List<Stat> statsByAds(Collection<Long> adIds) {
        if (adIds == null || adIds.isEmpty()) {
            return List.of();
        }
        Long[] ids = adIds.toArray(new Long[0]);
        return mapper.stats(ids);
    }
}
