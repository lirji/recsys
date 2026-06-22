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
 * 与在线 {@code PacingService.charge} 一致。花费与转化均以<b>点击时刻</b>落在窗口内为口径,保证 CPA 同分母。
 *
 * <p><b>延迟转化纠偏(docs/05 §6,M6)</b>:近窗口的点击尚未来得及转化,直接 {@code COUNT(转化)} 会
 * 系统性偏低 → CPA 高估 → k 被过度调低、错杀好广告主。开启 {@code --delay-correct}(默认)时,读
 * {@link DelayModelJob} 拟合的延迟速率 λ({@link RedisKeys#AD_DELAY_LAMBDA}),对每个<b>已到达</b>转化按
 * 其点击的 elapsed 做 Horvitz–Thompson 加权 {@code 1/c(elapsed)}(完成曲线 {@code c(e)=1−e^(−λe)}),
 * 把"还在路上"的转化补回来,得无偏的"终值转化数"再算 CPA。λ 缺失或 {@code --delay-correct=false}
 * → 退化为原始计数(不纠偏)。{@code --min-completion} 给权重设上限(防极近点击权重爆炸)。
 *
 * <p>参数:--days(默认 7,只看近 N 天;<=0 表示全部)、--alpha(默认 0.5)、
 * --min(默认 0.2)、--max(默认 5.0)、--delay-correct(默认 true)、--min-completion(默认 0.05)。
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
        boolean delayCorrect = boolArg(args, "delay-correct", true);
        double minCompletion = dblArg(args, "min-completion", 0.05);
        String since = days > 0 ? "now() - interval '" + days + " days'" : "'epoch'";

        // 延迟转化模型:λ(1/天)。开启纠偏且 Redis 有 λ 时,对转化做 Horvitz–Thompson 加权;否则原始计数。
        double lambda = delayCorrect ? readLambda() : 0.0;
        boolean correcting = lambda > 0;

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

        // 3. 窗口内每个广告主的转化数。口径与花费同为"点击落在窗口内"(clk.ts >= since)。
        //    correcting=true 时按完成曲线对每个已到达转化加权 1/c(elapsed) → 终值转化数(无偏);
        //    否则原始计数。raw 始终统计,供日志对比纠偏幅度。
        Map<Long, Double> conversions = new HashMap<>();   // 终值(可能为小数)
        Map<Long, Long> rawConv = new HashMap<>();         // 已观测原始计数
        String weightExpr = correcting
                ? "SUM(1.0 / GREATEST(1 - exp(-" + lambda
                        + " * EXTRACT(EPOCH FROM (now() - clk.ts)) / 86400.0), " + minCompletion + "))"
                : "COUNT(*)";
        jdbc.query(
                "SELECT a.advertiser_id, " + weightExpr + " AS eventual, COUNT(*) AS raw " +
                "FROM ad_event cvt " +
                "JOIN ad a ON a.ad_id = cvt.ad_id AND a.optimization_type='OCPC' " +
                "JOIN ad_event clk ON clk.request_id = cvt.request_id AND clk.ad_id = cvt.ad_id " +
                "  AND clk.event_type='CLICK' " +
                "WHERE cvt.event_type='CONVERSION' AND cvt.ts <= now() AND clk.ts >= " + since + " " +
                "GROUP BY a.advertiser_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    conversions.put(rs.getLong("advertiser_id"), rs.getDouble("eventual"));
                    rawConv.put(rs.getLong("advertiser_id"), rs.getLong("raw"));
                });

        int updated = 0, held = 0;
        for (Map.Entry<Long, Double> e : targetCpa.entrySet()) {
            long adv = e.getKey();
            double target = e.getValue();
            double kOld = readK(adv);
            double conv = conversions.getOrDefault(adv, 0.0);   // 终值转化数(纠偏后)
            long raw = rawConv.getOrDefault(adv, 0L);           // 已观测原始转化数
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
            log.info("广告主 {} 目标CPA={} 实际CPA={} (花费{}/转化{}{}) k {}→{}",
                    adv, round2(target), round2(actualCpa), round2(cost),
                    correcting ? round2(conv) : (long) conv,
                    correcting ? "[原始" + raw + "]" : "", round4(kOld), round4(kNew));
        }
        log.info("ad-ocpc 完成:OCPC 广告主 {} 个,更新系数 {} 个,无转化保持 {} 个(窗口={}天,alpha={},"
                        + "延迟纠偏={})",
                targetCpa.size(), updated, held, days, alpha,
                correcting ? "λ=" + round6(lambda) + "(1/天)" : (delayCorrect ? "λ缺失→关闭" : "关闭"));
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

    /** 读延迟模型速率 λ(ad:delay:lambda,1/天);缺失/异常/非正 → 0(调用方据此关闭纠偏)。 */
    private double readLambda() {
        try {
            String s = redis.opsForValue().get(RedisKeys.AD_DELAY_LAMBDA);
            if (s != null && !s.isBlank()) {
                double v = Double.parseDouble(s.trim());
                if (Double.isFinite(v) && v > 0) {
                    return v;
                }
            }
        } catch (Exception ignored) {
            // 缺失/异常 → 不纠偏
        }
        return 0.0;
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

    private static double round6(double v) {
        return Math.round(v * 1_000_000) / 1_000_000.0;
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0)) : def;
    }

    private static double dblArg(ApplicationArguments a, String k, double def) {
        return a.containsOption(k) ? Double.parseDouble(a.getOptionValues(k).get(0)) : def;
    }

    private static boolean boolArg(ApplicationArguments a, String k, boolean def) {
        if (!a.containsOption(k)) {
            return def;
        }
        var vals = a.getOptionValues(k);
        return vals.isEmpty() || Boolean.parseBoolean(vals.get(0));
    }
}
