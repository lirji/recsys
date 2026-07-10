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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 作业 data-quality(E6):数据质量 / 分布漂移巡检 —— 把"部署后悄悄劣化"的隐患显性化。
 *
 * <p>三项检查(纯函数 {@link DataQuality}):
 * <ol>
 *   <li><b>Embedding 覆盖率</b>:item_embedding / item、user_embedding / 活跃用户;低于阈值 → 向量召回大面积失效;</li>
 *   <li><b>pCTR 校准偏差(ECE)</b>:从 {@code ad_event} 按 pctr_calib 分桶比对实际 CTR;偏大 → 计费/竞价被系统性带偏;</li>
 *   <li><b>类目分布 PSI</b>:近 N 天正反馈的类目分布 vs 全历史基线;PSI 大 → 流量/兴趣漂移,离线模型可能过时。</li>
 * </ol>
 * 每项越阈值即 WARN(可被日志告警捕获),明细写 {@code eval/data-quality-<ts>.csv}。缺表的检查跳过、不影响其余。
 *
 * <p>参数:--min-embedding-coverage(默认 0.9)、--max-ece(默认 0.05)、--max-psi(默认 0.25)、--days(PSI 近窗,默认 7)。
 */
@Component
public class DataQualityJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(DataQualityJob.class);
    private static final String OUT_DIR = "eval";

    private final JdbcTemplate jdbc;
    private final JdbcTemplate derived;   // #3:item_embedding/user_embedding 覆盖率计数走派生库

    public DataQualityJob(JdbcTemplate jdbc,
                          @org.springframework.beans.factory.annotation.Qualifier("derivedJdbc") JdbcTemplate derived) {
        this.jdbc = jdbc;
        this.derived = derived;
    }

    @Override
    public String name() {
        return "data-quality";
    }

    /** #2:行为读来源表(默认 user_behavior;run() 设、helper 读——离线作业单次运行、无并发)。 */
    private String bt = "user_behavior";
    private String it = "item";   // #3:item 读来源表(默认 item)
    private String aet = "ad_event";   // #3:ad_event 读来源表(默认 ad_event)

    @Override
    public void run(ApplicationArguments args) throws Exception {
        bt = BehaviorQuery.table(args);
        it = ItemQuery.table(args);
        aet = AdEventQuery.table(args);
        double minCoverage = doubleArg(args, "min-embedding-coverage", 0.9);
        double maxEce = doubleArg(args, "max-ece", 0.05);
        double maxPsi = doubleArg(args, "max-psi", 0.25);
        int days = intArg(args, "days", 7);

        Map<String, String> report = new LinkedHashMap<>();
        List<String> breaches = new ArrayList<>();

        // 1. Embedding 覆盖率
        try {
            long items = count("SELECT count(*) FROM " + it);
            long itemEmb = derived.queryForObject("SELECT count(*) FROM item_embedding", Long.class);
            long users = count("SELECT count(DISTINCT user_id) FROM " + bt + "");
            long userEmb = derived.queryForObject("SELECT count(*) FROM user_embedding", Long.class);
            double itemCov = DataQuality.coverage(itemEmb, items);
            double userCov = DataQuality.coverage(userEmb, users);
            report.put("item_embedding_coverage", fmt(itemCov));
            report.put("user_embedding_coverage", fmt(userCov));
            log.info("Embedding 覆盖率:item {}/{}={} | user {}/{}={}", itemEmb, items, fmt(itemCov),
                    userEmb, users, fmt(userCov));
            if (itemCov < minCoverage) {
                breaches.add(String.format(Locale.ROOT, "item_embedding 覆盖率 %.3f < %.2f", itemCov, minCoverage));
            }
            if (userCov < minCoverage) {
                breaches.add(String.format(Locale.ROOT, "user_embedding 覆盖率 %.3f < %.2f", userCov, minCoverage));
            }
        } catch (Exception e) {
            log.warn("Embedding 覆盖率检查跳过(表缺失?): {}", e.getMessage());
        }

        // 2. pCTR 校准偏差(ECE),来自 ad_event
        try {
            double ece = calibrationEce();
            if (!Double.isNaN(ece)) {
                report.put("pctr_ece", fmt(ece));
                log.info("pCTR 校准偏差 ECE = {}", fmt(ece));
                if (ece > maxEce) {
                    breaches.add(String.format(Locale.ROOT, "pCTR ECE %.3f > %.2f(校准可能失效)", ece, maxEce));
                }
            } else {
                log.info("pCTR 校准检查:ad_event 无足够曝光/校准数据,跳过");
            }
        } catch (Exception e) {
            log.warn("pCTR 校准检查跳过(ad_event 缺失?): {}", e.getMessage());
        }

        // 3. 类目分布 PSI(近 N 天 vs 全历史)
        try {
            double psi = categoryPsi(days);
            if (!Double.isNaN(psi)) {
                report.put("category_psi_" + days + "d", fmt(psi));
                report.put("category_psi_level", DataQuality.psiLevel(psi));
                log.info("类目分布 PSI(近 {} 天 vs 全历史)= {}({})", days, fmt(psi), DataQuality.psiLevel(psi));
                if (psi > maxPsi) {
                    breaches.add(String.format(Locale.ROOT, "类目 PSI %.3f > %.2f(分布显著漂移,建议重训)", psi, maxPsi));
                }
            } else {
                log.info("类目 PSI 检查:行为/类目数据不足,跳过");
            }
        } catch (Exception e) {
            log.warn("类目 PSI 检查跳过: {}", e.getMessage());
        }

        writeReport(report, breaches);

        if (breaches.isEmpty()) {
            log.info("✅ data-quality 巡检通过,无越阈值项");
        } else {
            log.warn("⚠️ data-quality 巡检发现 {} 项越阈值(需关注):", breaches.size());
            for (String b : breaches) {
                log.warn("   - {}", b);
            }
        }
    }

    /** 从 ad_event 按 pctr_calib 十分桶,比对实际 CTR,算 ECE。无数据返回 NaN。 */
    private double calibrationEce() {
        String sql =
                "WITH impr AS (" +
                "  SELECT request_id, ad_id, pctr_calib, " +
                "         width_bucket(pctr_calib, 0, 1, 10) AS bin " +
                "  FROM " + aet + " WHERE event_type='IMPRESSION' AND pctr_calib IS NOT NULL), " +
                "clk AS (SELECT DISTINCT request_id, ad_id FROM " + aet + " WHERE event_type='CLICK') " +
                "SELECT i.bin AS bin, count(*) AS n, avg(i.pctr_calib) AS mean_pred, " +
                "  avg(CASE WHEN c.request_id IS NOT NULL THEN 1.0 ELSE 0.0 END) AS actual " +
                "FROM impr i LEFT JOIN clk c ON c.request_id=i.request_id AND c.ad_id=i.ad_id " +
                "GROUP BY i.bin ORDER BY i.bin";
        List<long[]> counts = new ArrayList<>();
        List<double[]> preds = new ArrayList<>();
        jdbc.query(sql, rs -> {
            counts.add(new long[]{rs.getLong("n")});
            preds.add(new double[]{rs.getDouble("mean_pred"), rs.getDouble("actual")});
        });
        if (counts.isEmpty()) {
            return Double.NaN;
        }
        long[] n = new long[counts.size()];
        double[] mean = new double[counts.size()];
        double[] actual = new double[counts.size()];
        for (int i = 0; i < counts.size(); i++) {
            n[i] = counts.get(i)[0];
            mean[i] = preds.get(i)[0];
            actual[i] = preds.get(i)[1];
        }
        return DataQuality.expectedCalibrationError(n, mean, actual);
    }

    /** 近 N 天正反馈的类目分布 vs 全历史基线的 PSI。数据不足返回 NaN。 */
    private double categoryPsi(int days) {
        Map<String, Long> base = new LinkedHashMap<>();
        Map<String, Long> recent = new LinkedHashMap<>();
        jdbc.query(
                "SELECT i.category AS cat, count(*) AS c FROM " + bt + " b JOIN " + it + " i ON i.item_id=b.item_id " +
                "WHERE b.action IN ('CLICK','LIKE','PLAY','RATING') AND i.category IS NOT NULL GROUP BY i.category",
                rs -> { base.put(rs.getString("cat"), rs.getLong("c")); });
        jdbc.query(
                "SELECT i.category AS cat, count(*) AS c FROM " + bt + " b JOIN " + it + " i ON i.item_id=b.item_id " +
                "WHERE b.action IN ('CLICK','LIKE','PLAY','RATING') AND i.category IS NOT NULL " +
                "  AND b.ts >= now() - (? || ' days')::interval GROUP BY i.category",
                rs -> { recent.put(rs.getString("cat"), rs.getLong("c")); }, String.valueOf(days));
        if (base.isEmpty() || recent.isEmpty()) {
            return Double.NaN;
        }
        // 以基线类目全集对齐(recent 缺的类目计 0,PSI 内部 EPS 平滑)
        long[] e = new long[base.size()];
        long[] a = new long[base.size()];
        int i = 0;
        for (Map.Entry<String, Long> en : base.entrySet()) {
            e[i] = en.getValue();
            a[i] = recent.getOrDefault(en.getKey(), 0L);
            i++;
        }
        return DataQuality.psi(e, a);
    }

    private long count(String sql) {
        Long v = jdbc.queryForObject(sql, Long.class);
        return v == null ? 0 : v;
    }

    private void writeReport(Map<String, String> report, List<String> breaches) throws Exception {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path out = Path.of(OUT_DIR, "data-quality-" + ts + ".csv");
        Files.createDirectories(out.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("metric,value");
            w.newLine();
            for (Map.Entry<String, String> e : report.entrySet()) {
                w.write(e.getKey() + "," + e.getValue());
                w.newLine();
            }
            w.write("breaches," + breaches.size());
            w.newLine();
            for (String b : breaches) {
                w.write("breach,\"" + b.replace("\"", "'") + "\"");
                w.newLine();
            }
        }
        log.info("data-quality 报表已写入 {}", out.toAbsolutePath());
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return a.containsOption(k) ? Integer.parseInt(a.getOptionValues(k).get(0).trim()) : def;
    }

    private static double doubleArg(ApplicationArguments a, String k, double def) {
        return a.containsOption(k) ? Double.parseDouble(a.getOptionValues(k).get(0).trim()) : def;
    }
}
