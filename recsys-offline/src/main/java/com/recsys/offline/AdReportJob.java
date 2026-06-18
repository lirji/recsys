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
 * 作业 ad-report:广告变现报表,闭合搜索广告的在线侧(对应 docs/05 M5)。
 *
 * <p>消费 {@code ad_event}(IMPRESSION/CLICK/CONVERSION),按广告位次(position)聚合 +
 * 给出整体:
 * <ul>
 *   <li>曝光 / 点击 / 转化数;CTR=点击/曝光、CVR=转化/点击;</li>
 *   <li>收入 = Σ 被点击曝光的 charged_price(CPC:点击才计费);</li>
 *   <li>eCPM = 收入 / 曝光 × 1000;平均相关性。</li>
 * </ul>
 * 与离线 {@link EvalJob}(推荐质量)、{@link AbReportJob}(自然结果分桶 CTR)并列,构成广告侧评估闭环。
 *
 * <p>参数:--since(yyyy-MM-dd,默认全量)。
 */
@Component
public class AdReportJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(AdReportJob.class);
    private static final String OUT_DIR = "eval";

    private final JdbcTemplate jdbc;

    public AdReportJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "ad-report";
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String since = stringArg(args, "since", null);
        String timeFilter = since != null ? " AND i.ts >= ?::timestamp" : "";

        // 按位次聚合:曝光/点击/转化/收入/相关性。点击与曝光按 (request_id, ad_id) 关联。
        String sql =
                "SELECT i.position, " +
                "  COUNT(*) AS impressions, " +
                "  COUNT(clk.ad_id) AS clicks, " +
                "  COUNT(cvt.ad_id) AS conversions, " +
                "  SUM(CASE WHEN clk.ad_id IS NOT NULL THEN i.charged_price ELSE 0 END) AS revenue, " +
                "  AVG(i.relevance) AS avg_relevance " +
                "FROM ad_event i " +
                "LEFT JOIN (SELECT DISTINCT request_id, ad_id FROM ad_event WHERE event_type='CLICK') clk " +
                "  ON clk.request_id = i.request_id AND clk.ad_id = i.ad_id " +
                "LEFT JOIN (SELECT DISTINCT request_id, ad_id FROM ad_event WHERE event_type='CONVERSION') cvt " +
                "  ON cvt.request_id = i.request_id AND cvt.ad_id = i.ad_id " +
                "WHERE i.event_type = 'IMPRESSION'" + timeFilter +
                " GROUP BY i.position ORDER BY i.position";

        List<Row> rows = new ArrayList<>();
        Object[] params = since != null ? new Object[]{since} : new Object[]{};
        jdbc.query(sql, params, rs -> {
            Row r = new Row();
            r.position = rs.getInt("position");
            r.impressions = rs.getLong("impressions");
            r.clicks = rs.getLong("clicks");
            r.conversions = rs.getLong("conversions");
            r.revenue = rs.getDouble("revenue");
            r.relevance = rs.getDouble("avg_relevance");
            rows.add(r);
        });

        if (rows.isEmpty()) {
            log.warn("无广告曝光数据(ad_event)。先经 /api/search-ads 产生曝光,或跑 --job=sim-ad-events。");
            return;
        }

        Row total = new Row();
        total.position = -1;
        for (Row r : rows) {
            total.impressions += r.impressions;
            total.clicks += r.clicks;
            total.conversions += r.conversions;
            total.revenue += r.revenue;
        }

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path outFile = Path.of(OUT_DIR, "ad-report-" + ts + ".csv");
        Files.createDirectories(outFile.getParent());

        log.info(String.format("---- 广告变现报表(曝光 %d,点击 %d,转化 %d,收入 %.2f 元,eCPM %.2f,CTR %.4f)----",
                total.impressions, total.clicks, total.conversions, total.revenue,
                ecpm(total), ctr(total)));
        log.info(String.format("%-8s %12s %8s %8s %10s %8s %8s %10s",
                "position", "impressions", "clicks", "convs", "revenue", "ctr", "cvr", "ecpm"));
        try (BufferedWriter w = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
            w.write("position,impressions,clicks,conversions,revenue,ctr,cvr,ecpm,avg_relevance");
            w.newLine();
            for (Row r : rows) {
                logRow(r);
                w.write(String.format("%d,%d,%d,%d,%.4f,%.6f,%.6f,%.4f,%.4f",
                        r.position, r.impressions, r.clicks, r.conversions, r.revenue,
                        ctr(r), cvr(r), ecpm(r), r.relevance));
                w.newLine();
            }
            // 汇总行
            w.write(String.format("ALL,%d,%d,%d,%.4f,%.6f,%.6f,%.4f,",
                    total.impressions, total.clicks, total.conversions, total.revenue,
                    ctr(total), cvr(total), ecpm(total)));
            w.newLine();
        }
        log.info("ad-report 完成,报表已写入 {}", outFile.toAbsolutePath());
    }

    private static void logRow(Row r) {
        log.info(String.format("%-8d %12d %8d %8d %10.2f %8.4f %8.4f %10.2f",
                r.position, r.impressions, r.clicks, r.conversions, r.revenue,
                ctr(r), cvr(r), ecpm(r)));
    }

    private static double ctr(Row r) {
        return r.impressions == 0 ? 0 : (double) r.clicks / r.impressions;
    }

    private static double cvr(Row r) {
        return r.clicks == 0 ? 0 : (double) r.conversions / r.clicks;
    }

    private static double ecpm(Row r) {
        return r.impressions == 0 ? 0 : r.revenue / r.impressions * 1000;
    }

    private static final class Row {
        int position;
        long impressions;
        long clicks;
        long conversions;
        double revenue;
        double relevance;
    }

    private static String stringArg(ApplicationArguments a, String k, String def) {
        return a.containsOption(k) && !a.getOptionValues(k).isEmpty()
                ? a.getOptionValues(k).get(0).trim() : def;
    }
}
