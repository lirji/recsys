package com.recsys.advertiser;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 默认报表来源:直读 {@code ad_event} 聚合(#3 拆库前行为,回滚落点)。
 * {@code recsys.ad.report.source} 未设或 {@code db} 时激活。advertiser 的 {@code JdbcTemplate} 是 ShardingSphere
 * 数据源,{@code ad_event} 作 {@code !SINGLE} 表路由到 ds_0——ad-serving 未拆库时可达。
 */
@Component
@ConditionalOnProperty(name = "recsys.ad.report.source", havingValue = "db", matchIfMissing = true)
public class DbAdReportReader implements AdReportReader {

    private final JdbcTemplate jdbc;

    public DbAdReportReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<Long, Stats> statsByAds(Collection<Long> adIds) {
        Map<Long, Stats> out = new HashMap<>();
        if (adIds == null || adIds.isEmpty()) {
            return out;
        }
        Long[] ids = adIds.toArray(new Long[0]);
        jdbc.query(
                "SELECT ad_id, " +
                "  SUM(CASE WHEN event_type='IMPRESSION' THEN 1 ELSE 0 END) AS impr, " +
                "  SUM(CASE WHEN event_type='CLICK' THEN 1 ELSE 0 END) AS clk, " +
                "  SUM(CASE WHEN event_type='CONVERSION' THEN 1 ELSE 0 END) AS conv, " +
                "  COALESCE(SUM(CASE WHEN event_type='CLICK' THEN charged_price ELSE 0 END),0) AS spend " +
                "FROM ad_event WHERE ad_id = ANY(?) GROUP BY ad_id",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids)),
                (java.sql.ResultSet rs) -> {
                    out.put(rs.getLong("ad_id"), new Stats(
                            rs.getLong("impr"), rs.getLong("clk"), rs.getLong("conv"), rs.getDouble("spend")));
                });
        return out;
    }
}
