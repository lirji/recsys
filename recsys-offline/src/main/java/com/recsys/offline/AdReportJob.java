package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import com.recsys.offline.persistence.mapper.primary.AdReportMapper;
import com.recsys.offline.persistence.model.AdEventSource;
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

    private final AdReportMapper mapper;

    /** 报表仅依赖主源的具名统计查询，CSV及晚查询失败边界由作业控制。 */
    public AdReportJob(AdReportMapper mapper) {
        this.mapper = mapper;
    }

    /** 保留广告报表命令入口，供Runner记录统一状态。 */
    @Override
    public String name() {
        return "ad-report";
    }

    /** 风控计数不可确认时不输出完整报表，已发生的晚查询部分写入仍沿原边界保留。 */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        AdEventSource source = AdEventQuery.source(args);
        String since = stringArg(args, "since", null);
        List<Row> rows = new ArrayList<>();
        mapper.scanPositions(source, since, context -> {
            var input = context.getResultObject();
            Row r = new Row();
            r.position = input.position();
            r.impressions = input.impressions();
            r.clicks = input.clicks();
            r.conversions = input.conversions();
            r.revenue = input.revenue();
            r.relevance = input.relevance();
            rows.add(r);
        });

        if (rows.isEmpty()) {
            throw new PublicErrorException(ErrorCode.JOB_NO_AD_REPORT_DATA);
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

        long invalidClicks = countInvalidClicks(source, since);
        log.info(String.format("---- 广告变现报表(曝光 %d,点击 %d,转化 %d,收入 %.2f 元,eCPM %.2f,CTR %.4f;"
                        + "反作弊拦截无效点击 %d)----",
                total.impressions, total.clicks, total.conversions, total.revenue,
                ecpm(total), ctr(total), invalidClicks));
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

            // 广告分层 A/B:按 ad_bucket 聚合(对比各变体的 CTR/收入/eCPM)
            reportByBucket(source, since, w);
        }
        log.info("ad-report 完成,报表已写入 {}", outFile.toAbsolutePath());
    }

    /** 按 ad_bucket(广告分层 A/B 变体)聚合,日志打印 + 追加到 CSV(position 列写 bucket:&lt;name&gt;)。 */
    private void reportByBucket(AdEventSource source, String since, BufferedWriter w) throws java.io.IOException {
        List<String[]> buckets = new ArrayList<>();
        // 仍在位次和ALL行写出后查询，保持原晚失败可见的部分报表。
        mapper.scanBuckets(source, since, context -> {
            var input = context.getResultObject();
            Row r = new Row();
            r.impressions = input.impressions();
            r.clicks = input.clicks();
            r.conversions = input.conversions();
            r.revenue = input.revenue();
            buckets.add(new String[]{input.bucket(),
                    String.format("%d,%d,%d,%.4f,%.6f,%.6f,%.4f",
                            r.impressions, r.clicks, r.conversions, r.revenue, ctr(r), cvr(r), ecpm(r))});
        });
        if (buckets.size() <= 1) {
            return;   // 未开广告分层 A/B(只有一个桶/none),不输出分桶段
        }
        log.info("---- 广告分层 A/B(按 ad_bucket)----");
        log.info(String.format("%-16s %12s %8s %8s %10s %8s %8s %10s",
                "bucket", "impressions", "clicks", "convs", "revenue", "ctr", "cvr", "ecpm"));
        for (String[] b : buckets) {
            String[] f = b[1].split(",");
            log.info(String.format("%-16s %12s %8s %8s %10s %8s %8s %10s",
                    b[0], f[0], f[1], f[2], f[3], f[4], f[5], f[6]));
            w.write("bucket:" + b[0] + "," + b[1] + ",");
            w.newLine();
        }
    }

    private static void logRow(Row r) {
        log.info(String.format("%-8d %12d %8d %8d %10.2f %8.4f %8.4f %10.2f",
                r.position, r.impressions, r.clicks, r.conversions, r.revenue,
                ctr(r), cvr(r), ecpm(r)));
    }

    /** 反作弊拦截的无效点击数(event_type=INVALID_CLICK),供报表展示风控效果。 */
    private long countInvalidClicks(AdEventSource source, String since) {
        Long count;
        try {
            count = mapper.countInvalidClicks(source, since);
        } catch (RuntimeException failure) {
            throw new PublicErrorException(ErrorCode.JOB_INVALID_CLICK_COUNT_UNAVAILABLE, failure);
        }
        // COUNT正常只会返回非负数；未知结果不能伪造成未发生风险事件。
        if (count == null || count < 0) {
            throw new PublicErrorException(ErrorCode.JOB_INVALID_CLICK_COUNT_UNAVAILABLE);
        }
        return count;
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
        return JobArguments.text(a, k, def);
    }
}
