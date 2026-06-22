package com.recsys.ad;

import com.recsys.common.ad.SponsoredAd;
import com.recsys.common.constant.RedisKeys;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ad-event-logger");
        t.setDaemon(true);
        return t;
    });

    public AdEventLogger(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
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
     * 写归因键 ad:expo:{req}:{adId} = "advertiserId;bidwordId;pctrCalib;ecpm;charged;position"
     * (点击回查计费上下文:CPC 在点击时按 charged 扣预算)。
     */
    private void attribute(String requestId, List<SponsoredAd> ads) {
        try {
            for (SponsoredAd a : ads) {
                String v = a.advertiserId() + ";" + a.bidwordId() + ";" + a.pctrCalibrated()
                        + ";" + a.ecpm() + ";" + a.chargedPrice() + ";" + a.position();
                redis.opsForValue().set(RedisKeys.adExposure(requestId, a.adId()), v, ATTRIBUTION_TTL);
            }
        } catch (Exception e) {
            log.debug("写广告归因键失败(忽略) req={}: {}", requestId, e.getMessage());
        }
    }

    /**
     * 点击回查归因:取该次曝光算好的计费上下文(广告主 + GSP 单次点击扣费)。
     * Redis 缺失(过期/未开)→ 回退查 ad_event 曝光行 + ad 表。查不到返回 null。
     */
    public ClickAttribution readAttribution(String requestId, long adId) {
        try {
            String v = redis.opsForValue().get(RedisKeys.adExposure(requestId, adId));
            if (v != null) {
                String[] p = v.split(";");
                return new ClickAttribution(Long.parseLong(p[0]), Double.parseDouble(p[4]));
            }
        } catch (Exception e) {
            log.debug("读广告归因键失败,回退 DB req={} ad={}: {}", requestId, adId, e.getMessage());
        }
        try {
            return jdbc.queryForObject(
                    "SELECT adv.advertiser_id, e.charged_price FROM ad_event e " +
                    "JOIN ad adv ON adv.ad_id = e.ad_id " +
                    "WHERE e.request_id = ? AND e.ad_id = ? AND e.event_type = 'IMPRESSION' LIMIT 1",
                    (rs, n) -> new ClickAttribution(rs.getLong("advertiser_id"), rs.getDouble("charged_price")),
                    requestId, adId);
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

    /** 点击计费上下文。 */
    public record ClickAttribution(long advertiserId, double chargedPrice) {
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
