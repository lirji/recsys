package com.recsys.offline;

import com.recsys.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 作业 ad-delay:**延迟转化建模(Delayed Feedback,docs/05 §6,M6)** 的离线拟合环节。
 *
 * <p><b>解决什么</b>:转化(CONVERSION)在点击(CLICK)后 T+N 天才到达。任何"按近窗口数转化"的口径
 * (尤其 {@link OcpcCalibrateJob} 的"实际 CPA = 花费/转化")都会因为<b>近期点击尚未来得及转化</b>而
 * 系统性低估转化、高估 CPA。要纠偏,先得知道"延迟有多长"。
 *
 * <p><b>怎么建模</b>:把"已转化的点击"的转化延迟 {@code d = 转化时刻 − 点击时刻} 建模成<b>指数分布</b>,
 * 速率 λ(单位 1/天)。转化<b>完成曲线</b> {@code c(e) = P(延迟 ≤ e) = 1 − e^(−λ·e)} —— 一次 elapsed=e 的
 * 点击若终将转化,到现在已经转化的概率就是 c(e)。下游 {@code ad-ocpc} 据此把每个已观测转化按
 * {@code 1/c(elapsed)} 加权(Horvitz–Thompson),补回"还在路上"的转化。
 *
 * <p><b>估计</b>:指数分布 MLE 即 {@code λ = 1/mean(观测延迟)}。只用已到达(转化时刻 ≤ now)的样本——
 * 这会因右删失而<b>略微高估 λ</b>(长延迟样本尚未出现),但对教学纠偏足够;真实系统应做带删失的
 * Delayed Feedback Model(Chapelle 2014)联合估计转化概率与延迟。样本过少({@code < --min-samples})则
 * 不写 Redis(下游退化为不纠偏,宁可不动也不乱估)。
 *
 * <p>写出:Redis {@link RedisKeys#AD_DELAY_LAMBDA}(λ,1/天)+ {@link RedisKeys#AD_DELAY_MEAN_DAYS}(均值,天)。
 *
 * <p>参数:--days(只用近 N 天的点击拟合,默认 30;<=0 表示全部)、--min-samples(默认 50)、
 * --max-delay-days(护栏:观测延迟超过此值视为异常丢弃,默认 30)。
 */
@Component
public class DelayModelJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(DelayModelJob.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public DelayModelJob(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @Override
    public String name() {
        return "ad-delay";
    }

    @Override
    public void run(ApplicationArguments args) {
        int days = intArg(args, "days", 30);
        int minSamples = intArg(args, "min-samples", 50);
        double maxDelayDays = dblArg(args, "max-delay-days", 30.0);
        String since = days > 0 ? "clk.ts >= now() - interval '" + days + " days'" : "TRUE";

        // 已观测转化的延迟(天):转化时刻 − 其点击时刻,按 request_id+ad_id 归因点击。
        // 只取转化时刻 ≤ now(已到达)且延迟在 [0, maxDelay] 内的样本。
        Double[] agg = new Double[]{0.0, 0.0};  // [count, sumDelayDays]
        jdbc.query(
                "SELECT EXTRACT(EPOCH FROM (cvt.ts - clk.ts)) / 86400.0 AS delay_days " +
                "FROM ad_event cvt " +
                "JOIN ad_event clk ON clk.request_id = cvt.request_id AND clk.ad_id = cvt.ad_id " +
                "  AND clk.event_type='CLICK' " +
                "WHERE cvt.event_type='CONVERSION' AND cvt.ts <= now() AND " + since,
                rs -> {
                    double d = rs.getDouble("delay_days");
                    if (d >= 0 && d <= maxDelayDays) {
                        agg[0] += 1;
                        agg[1] += d;
                    }
                });

        long n = (long) (double) agg[0];
        if (n < minSamples) {
            log.warn("ad-delay:可用延迟样本 {} < min-samples {},不更新延迟模型(下游退化为不纠偏)。"
                    + "先跑 --job=sim-ad-events 造带延迟的转化", n, minSamples);
            return;
        }
        double meanDays = agg[1] / n;
        if (meanDays <= 0) {
            log.warn("ad-delay:平均延迟 {} 非正,跳过", meanDays);
            return;
        }
        double lambda = 1.0 / meanDays;

        redis.opsForValue().set(RedisKeys.AD_DELAY_LAMBDA, String.valueOf(round6(lambda)));
        redis.opsForValue().set(RedisKeys.AD_DELAY_MEAN_DAYS, String.valueOf(round4(meanDays)));

        log.info("ad-delay 完成:样本 {},平均转化延迟 {} 天,λ={} (1/天)。完成曲线 c(e)=1−e^(−λe):"
                        + "c(1d)={} c(3d)={} c(7d)={} c(14d)={}。在线/离线 ad-ocpc 据此做 Horvitz–Thompson 纠偏",
                n, round4(meanDays), round6(lambda),
                round4(completion(lambda, 1)), round4(completion(lambda, 3)),
                round4(completion(lambda, 7)), round4(completion(lambda, 14)));
    }

    private static double completion(double lambda, double elapsedDays) {
        return 1.0 - Math.exp(-lambda * elapsedDays);
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
}
