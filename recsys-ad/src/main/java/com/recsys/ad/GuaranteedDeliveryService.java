package com.recsys.ad;

import com.recsys.common.ad.AdChannel;
import com.recsys.common.ad.SponsoredAd;
import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 品牌广告 / GD 保量在线分配(A4)——效果广告走竞价,品牌广告走"合约保量":在约定周期内保证交付
 * guaranteed 次曝光。本服务按<b>投放进度 pacing</b>({@link GdPacing})挑一条<b>最落后</b>的合约广告,
 * 由编排层置于广告位<b>首位</b>(保量优先、竞价广告让位),曝光时 INCR 交付计数。
 *
 * <p>不落后(超前 / 已交付满 / 未到窗口)的合约不参与,把广告位让给竞价广告 —— 既保量又不过度挤占竞价收入。
 * GD 广告按合约 CPM 结算(chargedPrice=price_cpm/1000,曝光计费),不进 GSP 拍卖。
 *
 * <p>优雅降级:开关 {@code recsys.ad.gd.enabled} 默认关;无合约 / 无落后合约 / 异常 → 返回空(纯竞价,行为不变)。
 * ad_contract 是单表(ds_0)走主库;ad 详情走分片库(经 {@link AdRepository})。
 */
@Service
public class GuaranteedDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(GuaranteedDeliveryService.class);

    private final JdbcTemplate jdbc;                 // 主库:ad_contract(单表)
    private final AdCatalogReader catalog;           // 目录读(sharded|replica):ad 详情
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final AdProperties props;

    public GuaranteedDeliveryService(JdbcTemplate jdbc, AdCatalogReader catalog,
                                     ObjectProvider<StringRedisTemplate> redisProvider, AdProperties props) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.redisProvider = redisProvider;
        this.props = props;
    }

    /** 选中的 GD 广告 + 其合约 id(曝光后据此记交付)。 */
    public record GdSelection(SponsoredAd ad, long contractId) {
    }

    /** 挑一条最落后的合约广告置于首位;无落后合约 / 未启用 → empty(纯竞价)。 */
    public Optional<GdSelection> select(long userId, int position) {
        if (!props.getGd().isEnabled()) {
            return Optional.empty();
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        long now = System.currentTimeMillis();
        double tol = props.getGd().getTolerance();
        try {
            List<long[]> contracts = jdbc.query(
                    "SELECT contract_id, ad_id, advertiser_id, guaranteed, " +
                    "  (extract(epoch from start_ts)*1000)::bigint AS s, " +
                    "  (extract(epoch from end_ts)*1000)::bigint AS e, " +
                    "  (price_cpm*1000)::bigint AS cpm_milli " +
                    "FROM ad_contract WHERE status='active'",
                    (rs, n) -> new long[]{rs.getLong("contract_id"), rs.getLong("ad_id"),
                            rs.getLong("advertiser_id"), rs.getLong("guaranteed"),
                            rs.getLong("s"), rs.getLong("e"), rs.getLong("cpm_milli")});
            long bestContract = -1, bestAd = 0, bestAdv = 0, bestCpmMilli = 0;
            double bestUrg = tol;
            for (long[] c : contracts) {
                long delivered = delivered(redis, c[0]);
                double urg = GdPacing.urgency(delivered, c[3], c[4], c[5], now);
                if (urg > bestUrg) {
                    bestUrg = urg;
                    bestContract = c[0];
                    bestAd = c[1];
                    bestAdv = c[2];
                    bestCpmMilli = c[6];
                }
            }
            if (bestContract < 0) {
                return Optional.empty();   // 无落后合约,全让位竞价
            }
            var detail = catalog.adItemAdvertiser(Set.of(bestAd)).get(bestAd);
            if (detail == null) {
                return Optional.empty();   // 合约广告非 active/approved,不出
            }
            double chargedPerImpr = bestCpmMilli / 1000.0 / 1000.0;   // price_cpm/1000 = 每次曝光价
            SponsoredAd ad = new SponsoredAd(bestAd, detail[0], bestAdv, 0L, "",
                    AdChannel.HOT_AD, 0.0, 1.0, 1.0, 0.0, 0.0, 0.0, chargedPerImpr, position, 0L, "CPM");
            return Optional.of(new GdSelection(ad, bestContract));
        } catch (Exception e) {
            log.debug("GD 保量选择失败(退纯竞价): {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 记一次 GD 曝光交付(INCR)。Redis 不可用则跳过(下次巡检仍按 DB 起点算,略偏保守)。 */
    public void recordDelivery(long contractId) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            redis.opsForValue().increment(RedisKeys.adGdDelivered(contractId));
        } catch (Exception e) {
            log.debug("记 GD 交付失败 contract={}: {}", contractId, e.getMessage());
        }
    }

    private long delivered(StringRedisTemplate redis, long contractId) {
        if (redis == null) {
            return 0;
        }
        try {
            String v = redis.opsForValue().get(RedisKeys.adGdDelivered(contractId));
            return v == null ? 0 : Long.parseLong(v);
        } catch (Exception e) {
            return 0;
        }
    }
}
