package com.recsys.advertiser;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.recsys.advertiser.persistence.mapper.primary.AdvertiserMapper;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 默认报表来源:直读 {@code ad_event} 聚合(#3 拆库前行为,回滚落点)。
 * {@code recsys.ad.report.source} 未设或 {@code db} 时激活。advertiser 的主Mapper绑定 ShardingSphere
 * 数据源,{@code ad_event} 作 {@code !SINGLE} 表路由到 ds_0——ad-serving 未拆库时可达。
 */
@Component
@ConditionalOnProperty(name = "recsys.ad.report.source", havingValue = "db", matchIfMissing = true)
public class DbAdReportReader implements AdReportReader {

    private final AdvertiserMapper mapper;

    /** 报表与广告目录共享原分片逻辑源，保留SINGLE表路由。 */
    public DbAdReportReader(AdvertiserMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Map<Long, Stats> statsByAds(Collection<Long> adIds) {
        Map<Long, Stats> out = new HashMap<>();
        if (adIds == null || adIds.isEmpty()) {
            return out;
        }
        Long[] ids = adIds.toArray(new Long[0]);
        mapper.reportStats(ids).forEach(row -> out.put(row.adId(), new Stats(
                row.impressions(), row.clicks(), row.conversions(), row.spend())));
        return out;
    }
}
