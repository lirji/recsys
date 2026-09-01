package com.recsys.offline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 作业 ab-report:在线分桶 CTR 报表,闭合分层 A/B 实验的在线侧。
 *
 * <p>消费 {@code ExposureLogger} 已写入的曝光埋点(user_behavior 中 action=IMPRESSION、
 * 带 bucket 标记的行),按 bucket 聚合:
 * <ul>
 *   <li>曝光数 = IMPRESSION 行数(分母);</li>
 *   <li>点击数 = CLICK 行数(分子);</li>
 *   <li>CTR = 点击 / 曝光;独立用户数。</li>
 * </ul>
 * 这样分层 A/B 的每个桶才有可读的线上指标,与离线 {@link EvalJob} 一起构成评估闭环。
 *
 * <p>口径说明:正反馈与曝光按相同的 bucket 关联(同一次推荐请求的曝光与后续点击落在同一桶)。
 * LIKE/PLAY/RATING 不计入 CTR,避免一次曝光产生多个正反馈时分子大于分母。
 *
 * <p><b>统计显著性(P2)</b>:除点估计 CTR 外,给每个桶算 Wilson 95% 置信区间,并相对<b>基线桶</b>
 * (默认曝光最多的桶,或 {@code --baseline=<name>})做两比例 z 检验 → z / 双侧 p 值 / 是否显著
 * (p<α,{@code --alpha} 默认 0.05)/ 相对提升 lift,以及"检测到该 lift 所需的每臂最小样本量"。
 * 这样才能判断"桶间 CTR 差异是真实的还是噪声"。<b>AA 校验</b>:把两个同策略桶当 A/B 跑本报表,
 * 若显著(p<α)即分桶/埋点有偏,须先修再做正式实验。
 *
 * <p>参数:--since(只统计该时间之后,格式 yyyy-MM-dd,默认全量)、--min-impressions
 * (桶曝光数下限,默认 1,过滤噪声小桶)、--baseline(基线桶名)、--alpha(显著性水平,默认 0.05)、
 * --power(算最小样本量的检验功效,默认 0.8)。传 {@code --cuped-pre-days=N} 时启用 CUPED:
 * {@code --since} 作为实验开始点,前置窗口严格为 [since-N 天,since),实验窗口为 [since,until),
 * {@code --until} 可选、{@code --scene} 可选、{@code --cuped-min-users} 默认 30。CUPED 以 user 为随机化
 * 单位并对 ratio metric 做影响函数线性化；跨 bucket 的 user 按首次曝光做 ITT 归因并显式报告污染数。
 * 不开启时保留旧 12 列 CSV 契约。
 */
@Component
public class AbReportJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(AbReportJob.class);
    private static final String OUT_DIR = "eval";

    private final JdbcTemplate jdbc;

    public AbReportJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "ab-report";
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String bt = BehaviorQuery.table(args);   // #2:行为读来源表(默认 user_behavior)
        String since = stringArg(args, "since", null);
        long minImpressions = intArg(args, "min-impressions", 1);
        String baselineArg = stringArg(args, "baseline", null);
        double alpha = doubleArg(args, "alpha", 0.05);
        double power = doubleArg(args, "power", 0.8);
        int cupedPreDays = intArg(args, "cuped-pre-days", 0);
        int cupedMinUsers = intArg(args, "cuped-min-users", 30);
        String until = stringArg(args, "until", null);
        String scene = stringArg(args, "scene", null);
        if (cupedPreDays < 0) {
            throw new IllegalArgumentException("--cuped-pre-days 不能为负数");
        }
        if (cupedPreDays > 0 && since == null) {
            throw new IllegalArgumentException("启用 CUPED(--cuped-pre-days>0)时必须提供 --since=实验开始时间");
        }
        if (cupedMinUsers < 2) {
            throw new IllegalArgumentException("--cuped-min-users 必须至少为 2");
        }
        double z = AbStats.inverseNormalCdf(1.0 - alpha / 2.0);  // 双侧置信/检验的临界 z

        // 时间过滤(可选);bucket 为空的行归为 '(none)'
        StringBuilder where = new StringBuilder("WHERE bucket IS NOT NULL");
        List<Object> params = new ArrayList<>();
        if (since != null) {
            where.append(" AND ts >= ?::timestamp");
            params.add(since);
        }
        if (until != null) {
            where.append(" AND ts < ?::timestamp");
            params.add(until);
        }
        if (scene != null) {
            where.append(" AND scene = ?");
            params.add(scene);
        }

        String sql = "SELECT COALESCE(bucket, '(none)') AS bucket, " +
                "  COUNT(*) FILTER (WHERE action='IMPRESSION') AS impressions, " +
                "  COUNT(*) FILTER (WHERE action='CLICK') AS clicks, " +
                "  COUNT(DISTINCT user_id) AS users " +
                "FROM " + bt + " " + where +
                " GROUP BY bucket ORDER BY impressions DESC";

        List<Row> rows = new ArrayList<>();
        jdbc.query(sql, params.toArray(), rs -> {
            Row r = new Row();
            r.bucket = rs.getString("bucket");
            r.impressions = rs.getLong("impressions");
            r.clicks = rs.getLong("clicks");
            r.users = rs.getLong("users");
            rows.add(r);
        });

        CupedReport cuped = cupedPreDays > 0
                ? buildCupedReport(bt, since, until, scene, cupedPreDays)
                : CupedReport.disabled();
        if (cuped.enabled) {
            // 开启 CUPED 时 raw/adjusted 必须使用同一首曝光 ITT 人群，避免一行内混用 as-treated 与 ITT。
            rows.clear();
            cuped.summaries.forEach((bucket, summary) -> {
                Row r = new Row();
                r.bucket = bucket;
                r.impressions = Math.round(summary.totalDenominator());
                r.clicks = Math.round(summary.totalNumerator());
                r.users = summary.units();
                rows.add(r);
            });
            rows.sort((a, b) -> Long.compare(b.impressions, a.impressions));
        }

        rows.removeIf(r -> r.impressions < minImpressions);
        if (rows.isEmpty()) {
            log.warn("无分桶曝光数据(IMPRESSION 行)。线上侧需先经 rec-engine 推荐(ExposureLogger 写曝光)再统计;" +
                    "若刚导入历史评分,user_behavior 里还没有 IMPRESSION 行。");
            return;
        }

        long totalImp = rows.stream().mapToLong(r -> r.impressions).sum();
        long totalClk = rows.stream().mapToLong(r -> r.clicks).sum();

        // 基线桶:显式 --baseline 优先,否则取曝光最多的桶(rows 已按 impressions 降序)
        Row base = rows.get(0);
        if (baselineArg != null) {
            base = rows.stream().filter(r -> baselineArg.equals(r.bucket)).findFirst().orElse(base);
        }
        double baseCtr = base.impressions == 0 ? 0 : (double) base.clicks / base.impressions;
        Map<String, CupedResult> cupedByBucket = evaluateCuped(
                cuped, rows.stream().map(r -> r.bucket).toList(), base.bucket, cupedMinUsers, alpha);

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path outFile = Path.of(OUT_DIR, "ab-report-" + ts + ".csv");
        Files.createDirectories(outFile.getParent());

        double overallCtr = totalImp == 0 ? 0 : (double) totalClk / totalImp;
        log.info(String.format("---- A/B 分桶报表(共 %d 桶,曝光 %d,点击 %d,整体 CTR %.4f;基线=%s,α=%.3f)----",
                rows.size(), totalImp, totalClk, overallCtr, base.bucket, alpha));
        if (cuped.enabled) {
            log.info("CUPED:pre={}天,pairedUsers={},θ={},方差降低={}%,跨桶用户(首曝光ITT)={}",
                    cupedPreDays, cuped.model.pairedUnits(), fmt(cuped.model.theta()),
                    fmt(cuped.model.varianceReduction() * 100), cuped.crossoverUsers);
        }
        log.info(String.format("%-24s %11s %8s %8s %-15s %8s %8s %6s %10s",
                "bucket", "impr", "clicks", "ctr", "ctr_95ci", "lift%", "p", "sig", "min_n/arm"));
        try (BufferedWriter w = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
            w.write("bucket,impressions,clicks,ctr,ctr_ci_low,ctr_ci_high,users,"
                    + "lift_vs_base,z,p_value,significant,min_sample_per_arm");
            if (cuped.enabled) {
                w.write(",cuped_status,cuped_users,cuped_covariate_coverage,cuped_theta,"
                        + "cuped_adjusted_ctr,cuped_lift_vs_base,cuped_se,cuped_z,cuped_p_value,"
                        + "cuped_significant,cuped_variance_reduction,cuped_crossover_users");
            }
            w.newLine();
            for (Row r : rows) {
                double ctr = r.impressions == 0 ? 0 : (double) r.clicks / r.impressions;
                double[] ci = AbStats.wilson(r.clicks, r.impressions, z);
                boolean isBase = r.bucket.equals(base.bucket);
                // 相对基线的推断(基线桶自身留空)
                double lift = baseCtr == 0 ? 0 : (ctr - baseCtr) / baseCtr;
                double zStat = isBase ? 0 : AbStats.twoProportionZ(r.clicks, r.impressions, base.clicks, base.impressions);
                double p = isBase ? 1.0 : AbStats.twoSidedPValue(zStat);
                boolean sig = !isBase && p < alpha;
                long minN = isBase ? 0 : AbStats.minSamplePerArm(ctr, baseCtr, alpha, power);
                CupedResult cr = cupedByBucket.get(r.bucket);

                log.info(String.format("%-24s %11d %8d %8.4f [%.4f,%.4f] %7s %8s %6s %10s",
                        r.bucket, r.impressions, r.clicks, ctr, ci[0], ci[1],
                        isBase ? "base" : String.format("%+.1f", lift * 100),
                        isBase ? "-" : fmtP(p), isBase ? "-" : (sig ? "YES" : "no"),
                        isBase ? "-" : (minN == Long.MAX_VALUE ? "inf" : String.valueOf(minN))));
                w.write(String.format(Locale.ROOT, "%s,%d,%d,%.6f,%.6f,%.6f,%d,%s,%.4f,%.6f,%s,%s",
                        r.bucket, r.impressions, r.clicks, ctr, ci[0], ci[1], r.users,
                        isBase ? "" : String.format(Locale.ROOT, "%.6f", lift),
                        isBase ? 0.0 : zStat, p, isBase ? "" : String.valueOf(sig),
                        isBase ? "" : (minN == Long.MAX_VALUE ? "inf" : String.valueOf(minN))));
                if (cuped.enabled) {
                    w.write("," + cupedCsv(cr));
                }
                w.newLine();
            }
        }
        log.info("ab-report 完成,报表已写入 {}", outFile.toAbsolutePath());
        log.info("解读:sig=YES 表示该桶 CTR 与基线差异在 α={} 下显著;若两个<b>同策略</b>桶间也 sig,"
                + "则分桶/埋点存在偏差(AA 校验失败),须先修再做正式 A/B。", alpha);
    }

    /** 从严格不重叠的 pre/post 窗口组装 user 级 CUPED 输入。 */
    private CupedReport buildCupedReport(String table, String since, String until, String scene,
                                         int preDays) {
        StringBuilder postWhere = new StringBuilder(
                "WHERE bucket IS NOT NULL AND ts >= ?::timestamp");
        List<Object> postParams = new ArrayList<>();
        postParams.add(since);
        if (until != null) {
            postWhere.append(" AND ts < ?::timestamp");
            postParams.add(until);
        }
        if (scene != null) {
            postWhere.append(" AND scene = ?");
            postParams.add(scene);
        }
        String postSql = "SELECT user_id,bucket," +
                " COUNT(*) FILTER (WHERE action='IMPRESSION') AS impressions," +
                " COUNT(*) FILTER (WHERE action='CLICK') AS clicks," +
                " MIN(ts) FILTER (WHERE action='IMPRESSION') AS first_impression" +
                " FROM " + table + " " + postWhere + " GROUP BY user_id,bucket" +
                " HAVING COUNT(*) FILTER (WHERE action='IMPRESSION') > 0";
        List<UserBucketPeriod> post = new ArrayList<>();
        jdbc.query(postSql, postParams.toArray(), (RowCallbackHandler) rs -> post.add(new UserBucketPeriod(
                rs.getLong("user_id"), rs.getString("bucket"),
                rs.getLong("impressions"), rs.getLong("clicks"),
                rs.getTimestamp("first_impression").getTime())));

        StringBuilder preWhere = new StringBuilder(
                "WHERE ts >= ?::timestamp - (? * interval '1 day') AND ts < ?::timestamp");
        List<Object> preParams = new ArrayList<>();
        preParams.add(since);
        preParams.add(preDays);
        preParams.add(since);
        if (scene != null) {
            preWhere.append(" AND scene = ?");
            preParams.add(scene);
        }
        String preSql = "SELECT user_id," +
                " COUNT(*) FILTER (WHERE action='IMPRESSION') AS impressions," +
                " COUNT(*) FILTER (WHERE action='CLICK') AS clicks" +
                " FROM " + table + " " + preWhere + " GROUP BY user_id" +
                " HAVING COUNT(*) FILTER (WHERE action='IMPRESSION') > 0";
        Map<Long, PeriodTotals> pre = new HashMap<>();
        jdbc.query(preSql, preParams.toArray(), (RowCallbackHandler) rs -> {
            pre.put(rs.getLong("user_id"),
                    new PeriodTotals(rs.getLong("impressions"), rs.getLong("clicks")));
        });

        // 随机化单位是 user。跨桶属于 treatment 污染；按首次曝光桶做 intention-to-treat，
        // 保留全部 post 结果，避免“事后排除 crossover”造成选择偏差，同时报告污染用户数。
        Map<Long, List<UserBucketPeriod>> periodsByUser = new LinkedHashMap<>();
        for (UserBucketPeriod p : post) {
            periodsByUser.computeIfAbsent(p.userId, k -> new ArrayList<>()).add(p);
        }
        long pooledPreImpressions = periodsByUser.keySet().stream()
                .map(pre::get).filter(java.util.Objects::nonNull)
                .mapToLong(PeriodTotals::impressions).sum();
        long pooledPreClicks = periodsByUser.keySet().stream()
                .map(pre::get).filter(java.util.Objects::nonNull)
                .mapToLong(PeriodTotals::clicks).sum();
        double pooledPreCtr = pooledPreImpressions == 0 ? 0.0
                : (double) pooledPreClicks / pooledPreImpressions;

        Map<String, List<AbStats.CupedObservation>> byBucket = new LinkedHashMap<>();
        List<AbStats.CupedObservation> pooled = new ArrayList<>();
        int crossoverUsers = 0;
        for (Map.Entry<Long, List<UserBucketPeriod>> entry : periodsByUser.entrySet()) {
            List<UserBucketPeriod> periods = entry.getValue();
            if (periods.size() > 1) {
                crossoverUsers++;
            }
            UserBucketPeriod assigned = periods.stream()
                    .min(java.util.Comparator.comparingLong(UserBucketPeriod::firstImpression)
                            .thenComparing(UserBucketPeriod::bucket))
                    .orElseThrow();
            long impressions = periods.stream().mapToLong(UserBucketPeriod::impressions).sum();
            long clicks = periods.stream().mapToLong(UserBucketPeriod::clicks).sum();
            PeriodTotals prePeriod = pre.get(entry.getKey());
            Double preInfluence = prePeriod == null ? null
                    : prePeriod.clicks - pooledPreCtr * prePeriod.impressions;
            AbStats.CupedObservation o = new AbStats.CupedObservation(
                    clicks, impressions, preInfluence);
            byBucket.computeIfAbsent(assigned.bucket, k -> new ArrayList<>()).add(o);
            pooled.add(o);
        }
        AbStats.CupedModel model = AbStats.fitCuped(pooled);
        Map<String, AbStats.CupedSummary> summaries = new LinkedHashMap<>();
        byBucket.forEach((bucket, observations) ->
                summaries.put(bucket, AbStats.summarizeCuped(observations, model)));
        return new CupedReport(true, model, summaries, crossoverUsers);
    }

    static Map<String, CupedResult> evaluateCuped(CupedReport report, List<String> buckets,
                                                   String baseline, int minUsers, double alpha) {
        if (!report.enabled) {
            return Map.of();
        }
        Map<String, CupedResult> out = new LinkedHashMap<>();
        AbStats.CupedSummary base = report.summaries.get(baseline);
        for (String bucket : buckets) {
            AbStats.CupedSummary s = report.summaries.getOrDefault(bucket, CupedResult.EMPTY_SUMMARY);
            boolean isBase = bucket.equals(baseline);
            String status;
            if (s.units() == 0) {
                status = "no_eligible_users";
            } else if (base == null || base.units() < minUsers || base.covariateUnits() < minUsers) {
                status = "baseline_insufficient";
            } else if (s.units() < minUsers || s.covariateUnits() < minUsers) {
                status = "insufficient_users";
            } else if (report.model.pairedUnits() < 2 * minUsers) {
                status = "insufficient_paired_users";
            } else if (!report.model.applied()) {
                status = "no_covariate_variance";
            } else {
                status = "applied";
            }
            double lift = isBase || base == null || base.adjustedMean() == 0 ? 0.0
                    : (s.adjustedMean() - base.adjustedMean()) / base.adjustedMean();
            double z = isBase || !"applied".equals(status) ? 0.0 : AbStats.cupedDifferenceZ(s, base);
            if (!isBase && "applied".equals(status) && !Double.isFinite(z)) {
                status = "insufficient_variance";
                z = 0.0;
            }
            double p = isBase ? 1.0 : ("applied".equals(status) ? AbStats.twoSidedPValue(z) : 1.0);
            out.put(bucket, new CupedResult(status, s, report.model.theta(), lift, z, p,
                    !isBase && "applied".equals(status) && p < alpha,
                    report.model.varianceReduction(), report.crossoverUsers, isBase));
        }
        return out;
    }

    static String cupedCsv(CupedResult r) {
        if (r == null) {
            return ",,,,,,,,,,,";
        }
        return String.format(Locale.ROOT, "%s,%d,%.6f,%.6f,%.6f,%s,%.6f,%.6f,%.6f,%s,%.6f,%d",
                r.status, r.summary.units(), r.summary.covariateCoverage(), r.theta,
                r.summary.adjustedMean(), r.isBase ? "" : String.format(Locale.ROOT, "%.6f", r.lift),
                r.summary.standardError(), r.z, r.p,
                r.isBase ? "" : String.valueOf(r.significant), r.varianceReduction, r.crossoverUsers);
    }

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }

    private static String fmtP(double p) {
        return p < 0.0001 ? "<1e-4" : String.format("%.4f", p);
    }

    private static final class Row {
        String bucket;
        long impressions;
        long clicks;
        long users;
    }

    private record UserBucketPeriod(long userId, String bucket, long impressions, long clicks,
                                    long firstImpression) {
    }

    private record PeriodTotals(long impressions, long clicks) {
    }

    record CupedReport(boolean enabled, AbStats.CupedModel model,
                       Map<String, AbStats.CupedSummary> summaries, int crossoverUsers) {
        static CupedReport disabled() {
            return new CupedReport(false, new AbStats.CupedModel(0, 0, 0, 0, 0, false), Map.of(), 0);
        }
    }

    record CupedResult(String status, AbStats.CupedSummary summary, double theta,
                       double lift, double z, double p, boolean significant,
                       double varianceReduction, int crossoverUsers, boolean isBase) {
        private static final AbStats.CupedSummary EMPTY_SUMMARY =
                new AbStats.CupedSummary(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0).trim()) : def;
    }

    private static double doubleArg(ApplicationArguments a, String k, double def) {
        return a.containsOption(k) ? Double.parseDouble(a.getOptionValues(k).get(0).trim()) : def;
    }

    private static String stringArg(ApplicationArguments a, String k, String def) {
        return a.containsOption(k) && !a.getOptionValues(k).isEmpty()
                ? a.getOptionValues(k).get(0).trim() : def;
    }
}
