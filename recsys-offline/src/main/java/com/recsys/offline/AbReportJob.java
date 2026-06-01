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
 * <p>参数:--since(只统计该时间之后,格式 yyyy-MM-dd,默认全量)、--min-impressions
 * (桶曝光数下限,默认 1,过滤噪声小桶)。
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
        String since = stringArg(args, "since", null);
        long minImpressions = intArg(args, "min-impressions", 1);

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
                "FROM user_behavior " + where +
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

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path outFile = Path.of(OUT_DIR, "ab-report-" + ts + ".csv");
        Files.createDirectories(outFile.getParent());

        double overallCtr = totalImp == 0 ? 0 : (double) totalClk / totalImp;
        log.info(String.format("---- A/B 分桶报表(共 %d 桶,曝光 %d,点击 %d,整体 CTR %.4f)----",
                rows.size(), totalImp, totalClk, overallCtr));
        log.info(String.format("%-28s %12s %10s %8s %8s", "bucket", "impressions", "clicks", "ctr", "users"));
        try (BufferedWriter w = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
            w.write("bucket,impressions,clicks,ctr,users");
            w.newLine();
            for (Row r : rows) {
                double ctr = r.impressions == 0 ? 0 : (double) r.clicks / r.impressions;
                log.info(String.format("%-28s %12d %10d %8.4f %8d",
                        r.bucket, r.impressions, r.clicks, ctr, r.users));
                w.write(String.format("%s,%d,%d,%.6f,%d",
                        r.bucket, r.impressions, r.clicks, ctr, r.users));
                w.newLine();
            }
        }
        log.info("ab-report 完成,报表已写入 {}", outFile.toAbsolutePath());
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

    private static String stringArg(ApplicationArguments a, String k, String def) {
        return a.containsOption(k) && !a.getOptionValues(k).isEmpty()
                ? a.getOptionValues(k).get(0).trim() : def;
    }
}
