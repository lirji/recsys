package com.recsys.offline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 作业 ab-report:在线分桶 CTR 报表,闭合分层 A/B 实验的在线侧。
 *
 * <p>消费 {@code ExposureLogger} 已写入的曝光埋点(user_behavior 中 action=IMPRESSION、
 * 带 bucket 标记的行),按 bucket 聚合:
 * <ul>
 *   <li>曝光数 = IMPRESSION 行数(分母);</li>
 *   <li>正反馈数 = CLICK/LIKE/PLAY 行数(分子);</li>
 *   <li>CTR = 正反馈 / 曝光;独立用户数。</li>
 * </ul>
 * 这样分层 A/B 的每个桶才有可读的线上指标,与离线 {@link EvalJob} 一起构成评估闭环。
 *
 * <p>口径说明:正反馈与曝光按相同的 bucket 关联(同一次推荐请求的曝光与后续点击落在同一桶)。
 * RATING 不计入点击(它是离线导入的历史评分,非线上交互),故只数 CLICK/LIKE/PLAY。
 *
 * <p><b>统计显著性(P2)</b>:除点估计 CTR 外,给每个桶算 Wilson 95% 置信区间,并相对<b>基线桶</b>
 * (默认曝光最多的桶,或 {@code --baseline=<name>})做两比例 z 检验 → z / 双侧 p 值 / 是否显著
 * (p<α,{@code --alpha} 默认 0.05)/ 相对提升 lift,以及"检测到该 lift 所需的每臂最小样本量"。
 * 这样才能判断"桶间 CTR 差异是真实的还是噪声"。<b>AA 校验</b>:把两个同策略桶当 A/B 跑本报表,
 * 若显著(p<α)即分桶/埋点有偏,须先修再做正式实验。
 *
 * <p>参数:--since(只统计该时间之后,格式 yyyy-MM-dd,默认全量)、--min-impressions
 * (桶曝光数下限,默认 1,过滤噪声小桶)、--baseline(基线桶名)、--alpha(显著性水平,默认 0.05)、
 * --power(算最小样本量的检验功效,默认 0.8)。
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
        double z = AbStats.inverseNormalCdf(1.0 - alpha / 2.0);  // 双侧置信/检验的临界 z

        // 时间过滤(可选);bucket 为空的行归为 '(none)'
        StringBuilder where = new StringBuilder("WHERE bucket IS NOT NULL");
        List<Object> params = new ArrayList<>();
        if (since != null) {
            where.append(" AND ts >= ?::timestamp");
            params.add(since);
        }

        String sql = "SELECT COALESCE(bucket, '(none)') AS bucket, " +
                "  COUNT(*) FILTER (WHERE action='IMPRESSION') AS impressions, " +
                "  COUNT(*) FILTER (WHERE action IN ('CLICK','LIKE','PLAY')) AS clicks, " +
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

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path outFile = Path.of(OUT_DIR, "ab-report-" + ts + ".csv");
        Files.createDirectories(outFile.getParent());

        double overallCtr = totalImp == 0 ? 0 : (double) totalClk / totalImp;
        log.info(String.format("---- A/B 分桶报表(共 %d 桶,曝光 %d,点击 %d,整体 CTR %.4f;基线=%s,α=%.3f)----",
                rows.size(), totalImp, totalClk, overallCtr, base.bucket, alpha));
        log.info(String.format("%-24s %11s %8s %8s %-15s %8s %8s %6s %10s",
                "bucket", "impr", "clicks", "ctr", "ctr_95ci", "lift%", "p", "sig", "min_n/arm"));
        try (BufferedWriter w = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
            w.write("bucket,impressions,clicks,ctr,ctr_ci_low,ctr_ci_high,users,"
                    + "lift_vs_base,z,p_value,significant,min_sample_per_arm");
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

                log.info(String.format("%-24s %11d %8d %8.4f [%.4f,%.4f] %7s %8s %6s %10s",
                        r.bucket, r.impressions, r.clicks, ctr, ci[0], ci[1],
                        isBase ? "base" : String.format("%+.1f", lift * 100),
                        isBase ? "-" : fmtP(p), isBase ? "-" : (sig ? "YES" : "no"),
                        isBase ? "-" : (minN == Long.MAX_VALUE ? "inf" : String.valueOf(minN))));
                w.write(String.format("%s,%d,%d,%.6f,%.6f,%.6f,%d,%s,%.4f,%.6f,%s,%s",
                        r.bucket, r.impressions, r.clicks, ctr, ci[0], ci[1], r.users,
                        isBase ? "" : String.format("%.6f", lift),
                        isBase ? 0.0 : zStat, p, isBase ? "" : String.valueOf(sig),
                        isBase ? "" : (minN == Long.MAX_VALUE ? "inf" : String.valueOf(minN))));
                w.newLine();
            }
        }
        log.info("ab-report 完成,报表已写入 {}", outFile.toAbsolutePath());
        log.info("解读:sig=YES 表示该桶 CTR 与基线差异在 α={} 下显著;若两个<b>同策略</b>桶间也 sig,"
                + "则分桶/埋点存在偏差(AA 校验失败),须先修再做正式 A/B。", alpha);
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
