package com.recsys.ad;

import com.recsys.common.ad.SponsoredAd;
import com.recsys.common.constant.RedisKeys;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 广告计费/曝光日志(ad_event)——审计 + 校准 + 结算的源(docs/05 §3)。
 *
 * <ul>
 *   <li><b>曝光</b>:每次竞得展示按 IMPRESSION 批量落库,带 pctr/pctr_calib/ecpm/charged_price/relevance,
 *       异步 fire-and-forget(不阻塞搜索主链路);同时写短 TTL 归因键供点击回查。</li>
 *   <li><b>点击/转化</b>:反馈接口同步落 CLICK/CONVERSION 行(低 QPS),与曝光按 (request_id, ad_id) 关联,
 *       供离线校准(pctr vs 实际 CTR)与变现报表。</li>
 * </ul>
 */
@Component
public class AdEventLogger {

    private static final Logger log = LoggerFactory.getLogger(AdEventLogger.class);
    private static final Duration ATTRIBUTION_TTL = Duration.ofHours(2);

    /** 主库:ad_event(单表 ds_0,不分片)。 */
    private final JdbcTemplate jdbc;
    /** 分片库:ad 表(查广告主归因用),按 advertiser_id 分布在 ds_0/ds_1。 */
    private final JdbcTemplate sharded;
    private final StringRedisTemplate redis;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ad-event-logger");
        t.setDaemon(true);
        return t;
    });

    public AdEventLogger(@Qualifier("adDbJdbc") JdbcTemplate jdbc, @Qualifier("adShardingJdbc") JdbcTemplate sharded,
                         StringRedisTemplate redis) {
        // #3:ad_event 读写走 adDbJdbc(默认 recsys,AD_PG_DB 设则 ad-serving 自有库);ad 表仍走 sharded。
        this.jdbc = jdbc;
        this.sharded = sharded;
        this.redis = redis;
    }

    /** 异步记录一次搜索的全部广告曝光。 */
    public void logImpressions(String requestId, String query, long userId, List<SponsoredAd> ads, String adBucket) {
        if (ads == null || ads.isEmpty()) {
            return;
        }
        executor.submit(() -> {
            insertImpressions(requestId, query, userId, ads, adBucket);
            attribute(requestId, ads);
        });
    }

    private void insertImpressions(String requestId, String query, long userId,
                                   List<SponsoredAd> ads, String adBucket) {
        try {
            jdbc.batchUpdate(
                    "INSERT INTO ad_event(request_id,query,user_id,ad_id,bidword_id,position,event_type," +
                    "pctr,pctr_calib,ecpm,charged_price,relevance,ad_bucket,creative_id) " +
                    "VALUES(?,?,?,?,?,?, 'IMPRESSION', ?,?,?,?,?,?,?)",
                    new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement ps, int i) throws SQLException {
                            SponsoredAd a = ads.get(i);
                            ps.setString(1, requestId);
                            ps.setString(2, query);
                            ps.setLong(3, userId);
                            ps.setLong(4, a.adId());
                            ps.setLong(5, a.bidwordId());
                            ps.setInt(6, a.position());
                            ps.setDouble(7, a.pctr());
                            ps.setDouble(8, a.pctrCalibrated());
                            ps.setDouble(9, a.ecpm());
                            ps.setDouble(10, a.chargedPrice());
                            ps.setDouble(11, a.relevance());
                            ps.setString(12, adBucket);
                            // DCO:展示创意归因(0/默认创意 → NULL,便于按真实创意聚合 CTR)
                            if (a.creativeId() > 0) {
                                ps.setLong(13, a.creativeId());
                            } else {
                                ps.setNull(13, java.sql.Types.BIGINT);
                            }
                        }

                        @Override
                        public int getBatchSize() {
                            return ads.size();
                        }
                    });
        } catch (Exception e) {
            log.warn("广告曝光落库失败 req={}: {}", requestId, e.getMessage());
        }
    }

    /**
     * 写归因键 ad:expo:{req}:{adId} = "advertiserId;bidwordId;pctrCalib;ecpm;charged;position;bidType"
     * (回查计费上下文:按 bidType 在点击/曝光/转化事件扣 charged)。
     */
    private void attribute(String requestId, List<SponsoredAd> ads) {
        try {
            for (SponsoredAd a : ads) {
                String v = a.advertiserId() + ";" + a.bidwordId() + ";" + a.pctrCalibrated()
                        + ";" + a.ecpm() + ";" + a.chargedPrice() + ";" + a.position()
                        + ";" + (a.bidType() == null ? "CPC" : a.bidType());
                redis.opsForValue().set(RedisKeys.adExposure(requestId, a.adId()), v, ATTRIBUTION_TTL);
            }
        } catch (Exception e) {
            log.debug("写广告归因键失败(忽略) req={}: {}", requestId, e.getMessage());
        }
    }

    /**
     * 回查归因:取该次曝光算好的计费上下文(广告主 + GSP 扣费额 + 计费模式)。
     * Redis 缺失(过期/未开)→ 回退查 ad_event 曝光行 + ad 表(含 optimization_type)。查不到返回 null。
     */
    public ClickAttribution readAttribution(String requestId, long adId) {
        try {
            String v = redis.opsForValue().get(RedisKeys.adExposure(requestId, adId));
            if (v != null) {
                String[] p = v.split(";");
                String bidType = p.length > 6 ? p[6] : "CPC";   // 兼容旧格式(无 bidType 段)
                return new ClickAttribution(Long.parseLong(p[0]), Double.parseDouble(p[4]), bidType);
            }
        } catch (Exception e) {
            log.debug("读广告归因键失败,回退 DB req={} ad={}: {}", requestId, adId, e.getMessage());
        }
        // 分库:ad_event(主库单表)与 ad(分片库)不能跨源 JOIN —— 先主库取计费额,再分片库按 ad_id 取广告主+计费模式。
        try {
            Double charged = jdbc.queryForObject(
                    "SELECT charged_price FROM ad_event " +
                    "WHERE request_id = ? AND ad_id = ? AND event_type = 'IMPRESSION' LIMIT 1",
                    Double.class, requestId, adId);
            if (charged == null) {
                return null;
            }
            var row = sharded.queryForMap(
                    "SELECT advertiser_id, optimization_type FROM ad WHERE ad_id = ?", adId);
            Long advertiserId = ((Number) row.get("advertiser_id")).longValue();
            String bidType = (String) row.get("optimization_type");
            return new ClickAttribution(advertiserId, charged, bidType == null ? "CPC" : bidType);
        } catch (Exception e) {
            return null;
        }
    }

    /** 同步落点击/转化反馈行。event 为 CLICK / CONVERSION。 */
    public void logFeedback(String requestId, long adId, long userId, String event) {
        try {
            jdbc.update(
                    "INSERT INTO ad_event(request_id,user_id,ad_id,event_type) VALUES(?,?,?,?)",
                    requestId, userId, adId, event);
        } catch (Exception e) {
            log.warn("广告反馈落库失败 req={} ad={} event={}: {}", requestId, adId, event, e.getMessage());
        }
    }

    /** 计费上下文(点击/曝光/转化回查):广告主 + GSP 扣费额 + 计费模式(决定在哪个事件扣)。 */
    public record ClickAttribution(long advertiserId, double chargedPrice, String bidType) {
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
