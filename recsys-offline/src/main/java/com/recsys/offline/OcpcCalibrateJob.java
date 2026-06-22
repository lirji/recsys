package com.recsys.offline;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 作业 ad-ocpc:用 {@code ad_event} 反馈控制拟合每个广告主的 oCPC 出价调节系数 k,
 * 写 Redis {@code ad:ocpc:{advertiserId}}(docs/05 §6,M6)。在线 {@link com.recsys.ad.OcpcBidder}
 * 出价 {@code bid = targetCpa × pCVR × k};本作业让"实际 CPA"收敛到广告主设的"目标 CPA"。
 *
 * <p><b>反馈控制</b>(对每个有 OCPC 广告的广告主):
 * <pre>
 *   实际 CPA = 窗口内花费 / 转化数
 *   k_new = clamp( k_old × (目标 CPA / 实际 CPA)^alpha , kMin, kMax )
 * </pre>
 * 实际 CPA 高于目标(超成本)→ 比值 &lt;1 → 调低 k → 降出价/降量;反之抬高。alpha 为阻尼,
 * 防一步过冲。无转化的广告主无法估计 → 保持原 k(宁可不动也不乱调)。
 *
 * <p>花费口径(CPC 计费):点击(CLICK)按其曝光(IMPRESSION)的 GSP 价 {@code charged_price} 累计,
 * 与在线 {@code PacingService.charge} 一致。
 *
 * <p>参数:--days(默认 7,只看近 N 天;<=0 表示全部)、--alpha(默认 0.5)、
 * --min(默认 0.2)、--max(默认 5.0)。
 */
@Component
public class OcpcCalibrateJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(OcpcCalibrateJob.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public OcpcCalibrateJob(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @Override
    public String name() {
        return "ad-ocpc";
    }

    @Override
    public void run(ApplicationArguments args) {
        int days = intArg(args, "days", 7);
        double alpha = dblArg(args, "alpha", 0.5);
        double kMin = dblArg(args, "min", 0.2);
        double kMax = dblArg(args, "max", 5.0);
        String since = days > 0 ? "now() - interval '" + days + " days'" : "'epoch'";

        // 1. 每个广告主的目标 CPA(其 OCPC 广告的均值)
        Map<Long, Double> targetCpa = new HashMap<>();
        jdbc.query(
                "SELECT advertiser_id, AVG(target_cpa) AS t FROM ad " +
                "WHERE optimization_type='OCPC' AND target_cpa > 0 GROUP BY advertiser_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        targetCpa.put(rs.getLong("advertiser_id"), rs.getDouble("t")));
        if (targetCpa.isEmpty()) {
            log.warn("无 OCPC 广告(先 --job=seed-ads),跳过");
            return;
        }

        // 2. 窗口内每个广告主的花费(点击归因到曝光 GSP 价)
        Map<Long, Double> spend = new HashMap<>();
        jdbc.query(
                "SELECT a.advertiser_id, SUM(imp.charged_price) AS s " +
                "FROM ad_event clk " +
                "JOIN ad a ON a.ad_id = clk.ad_id AND a.optimization_type='OCPC' " +
                "JOIN ad_event imp ON imp.request_id = clk.request_id AND imp.ad_id = clk.ad_id " +
                "  AND imp.event_type='IMPRESSION' " +
                "WHERE clk.event_type='CLICK' AND clk.ts >= " + since + " " +
                "GROUP BY a.advertiser_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        spend.put(rs.getLong("advertiser_id"), rs.getDouble("s")));

        // 3. 窗口内每个广告主的转化数
        Map<Long, Long> conversions = new HashMap<>();
        jdbc.query(
                "SELECT a.advertiser_id, COUNT(*) AS c " +
                "FROM ad_event ev " +
                "JOIN ad a ON a.ad_id = ev.ad_id AND a.optimization_type='OCPC' " +
                "WHERE ev.event_type='CONVERSION' AND ev.ts >= " + since + " " +
                "GROUP BY a.advertiser_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        conversions.put(rs.getLong("advertiser_id"), rs.getLong("c")));

        int updated = 0, held = 0;
        for (Map.Entry<Long, Double> e : targetCpa.entrySet()) {
            long adv = e.getKey();
            double target = e.getValue();
            double kOld = readK(adv);
            long conv = conversions.getOrDefault(adv, 0L);
            double cost = spend.getOrDefault(adv, 0.0);
            if (conv <= 0 || cost <= 0) {
                held++;
                log.debug("广告主 {} 无转化/花费,保持 k={}", adv, round4(kOld));
                continue;
            }
            double actualCpa = cost / conv;
            double kNew = clamp(kOld * Math.pow(target / actualCpa, alpha), kMin, kMax);
            redis.opsForValue().set(RedisKeys.adOcpc(adv), String.valueOf(round4(kNew)));
            updated++;
            log.info("广告主 {} 目标CPA={} 实际CPA={} (花费{}/转化{}) k {}→{}",
                    adv, round2(target), round2(actualCpa), round2(cost), conv, round4(kOld), round4(kNew));
        }
        log.info("ad-ocpc 完成:OCPC 广告主 {} 个,更新系数 {} 个,无转化保持 {} 个(窗口={}天,alpha={})",
                targetCpa.size(), updated, held, days, alpha);
    }

    private double readK(long advertiserId) {
        try {
            String s = redis.opsForValue().get(RedisKeys.adOcpc(advertiserId));
            if (s != null && !s.isBlank()) {
                double k = Double.parseDouble(s.trim());
                if (Double.isFinite(k) && k > 0) {
                    return k;
                }
            }
        } catch (Exception ignored) {
            // 缺失/异常 → 从 1.0 起步
        }
        return 1.0;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0)) : def;
    }

    private static double dblArg(ApplicationArguments a, String k, double def) {
        return a.containsOption(k) ? Double.parseDouble(a.getOptionValues(k).get(0)) : def;
    }
}
