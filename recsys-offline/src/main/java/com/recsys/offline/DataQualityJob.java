package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
import com.recsys.common.report.DataQualityCheck;
import com.recsys.common.report.DataQualityCheck.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import com.recsys.offline.persistence.mapper.primary.DataQualityInputMapper;
import com.recsys.offline.persistence.mapper.derived.DataQualityCoverageMapper;
import com.recsys.offline.persistence.model.BehaviorSource;
import com.recsys.offline.persistence.model.AdEventSource;
import com.recsys.content.persistence.model.CatalogTable;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * 作业 data-quality(E6):数据质量 / 分布漂移巡检 —— 把"部署后悄悄劣化"的隐患显性化。
 *
 * <p>三项检查(纯函数 {@link DataQuality}):
 * <ol>
 *   <li><b>Embedding 覆盖率</b>:item_embedding / item、user_embedding / 活跃用户;低于阈值 → 向量召回大面积失效;</li>
 *   <li><b>pCTR 校准偏差(ECE)</b>:从 {@code ad_event} 按 pctr_calib 分桶比对实际 CTR;偏大 → 计费/竞价被系统性带偏;</li>
 *   <li><b>类目分布 PSI</b>:近 N 天正反馈的类目分布 vs 全历史基线;PSI 大 → 流量/兴趣漂移,离线模型可能过时。</li>
 * </ol>
 * 每项越阈值即 WARN(可被日志告警捕获),明细写 {@code eval/data-quality-<ts>.csv}。缺表或无数据仍继续其余检查，但最终明确巡检未完成。
 *
 * <p>参数:--min-embedding-coverage(默认 0.9)、--max-ece(默认 0.05)、--max-psi(默认 0.25)、--days(PSI 近窗,默认 7)。
 */
@Component
public class DataQualityJob implements OfflineJob {

    private static final Logger log = LoggerFactory.getLogger(DataQualityJob.class);
    private static final String OUT_DIR = "eval";

    private final DataQualityInputMapper inputs;
    private final DataQualityCoverageMapper coverage;

    /** 主源分母/事件与派生源向量计数分开注入，避免巡检误读同名表。 */
    public DataQualityJob(DataQualityInputMapper inputs, DataQualityCoverageMapper coverage) {
        this.inputs = inputs;
        this.coverage = coverage;
    }

    /** 保留既有巡检命令名。 */
    @Override
    public String name() {
        return "data-quality";
    }

    /** 三项独立执行后持久化实际状态；失败或缺数据不能被零告警伪装成通过。 */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        BehaviorSource behaviorSource = BehaviorQuery.source(args);
        CatalogTable itemSource = ItemQuery.source(args);
        AdEventSource adSource = AdEventQuery.source(args);
        double minCoverage = doubleArg(args, "min-embedding-coverage", 0.9);
        double maxEce = doubleArg(args, "max-ece", 0.05);
        double maxPsi = doubleArg(args, "max-psi", 0.25);
        int days = intArg(args, "days", 7);
        if (minCoverage < 0 || minCoverage > 1 || maxEce < 0 || maxPsi < 0) {
            throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT);
        }
        Map<DataQualityCheck, Status> states = new EnumMap<>(DataQualityCheck.class);
        List<RuntimeException> failures = new ArrayList<>();

        Map<String, String> report = new LinkedHashMap<>();
        List<String> breaches = new ArrayList<>();

        // 1. Embedding 覆盖率
        try {
            long items = inputs.countItems(itemSource);
            long itemEmb = coverage.countItemEmbeddings();
            long users = inputs.countUsers(behaviorSource);
            long userEmb = coverage.countUserEmbeddings();
            if (items < 0 || itemEmb < 0 || users < 0 || userEmb < 0) throw invalidInput();
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
            states.put(DataQualityCheck.EMBEDDING, items == 0 || users == 0 ? Status.NO_DATA
                    : itemCov < minCoverage || userCov < minCoverage ? Status.BREACHED : Status.PASSED);
        } catch (RuntimeException failure) {
            failed(DataQualityCheck.EMBEDDING, failure, states, failures);
        }

        // 2. pCTR 校准偏差(ECE),来自 ad_event
        try {
            OptionalDouble measured = calibrationEce(adSource);
            if (measured.isPresent()) {
                double ece = measured.getAsDouble();
                report.put("pctr_ece", fmt(ece));
                log.info("pCTR 校准偏差 ECE = {}", fmt(ece));
                states.put(DataQualityCheck.CALIBRATION, ece > maxEce ? Status.BREACHED : Status.PASSED);
                if (ece > maxEce) {
                    breaches.add(String.format(Locale.ROOT, "pCTR ECE %.3f > %.2f(校准可能失效)", ece, maxEce));
                }
            } else {
                states.put(DataQualityCheck.CALIBRATION, Status.NO_DATA);
            }
        } catch (RuntimeException failure) {
            failed(DataQualityCheck.CALIBRATION, failure, states, failures);
        }

        // 3. 类目分布 PSI(近 N 天 vs 全历史)
        try {
            OptionalDouble measured = categoryPsi(behaviorSource, itemSource, days);
            if (measured.isPresent()) {
                double psi = measured.getAsDouble();
                report.put("category_psi_" + days + "d", fmt(psi));
                report.put("category_psi_level", DataQuality.psiLevel(psi));
                states.put(DataQualityCheck.CATEGORY, psi > maxPsi ? Status.BREACHED : Status.PASSED);
                log.info("类目分布 PSI(近 {} 天 vs 全历史)= {}({})", days, fmt(psi), DataQuality.psiLevel(psi));
                if (psi > maxPsi) {
                    breaches.add(String.format(Locale.ROOT, "类目 PSI %.3f > %.2f(分布显著漂移,建议重训)", psi, maxPsi));
                }
            } else {
                states.put(DataQualityCheck.CATEGORY, Status.NO_DATA);
            }
        } catch (RuntimeException failure) {
            failed(DataQualityCheck.CATEGORY, failure, states, failures);
        }

        try {
            writeReport(report, breaches, states);
        } catch (IOException failure) {
            throw new PublicErrorException(ErrorCode.JOB_REPORT_OUTPUT_FAILED, failure);
        }
        if (!failures.isEmpty()) {
            throw new PublicErrorException(ErrorCode.JOB_DATA_QUALITY_CHECK_FAILED, failures.getFirst());
        }
        if (states.containsValue(Status.NO_DATA)) {
            throw new PublicErrorException(ErrorCode.JOB_DATA_QUALITY_INCOMPLETE);
        }

        if (breaches.isEmpty()) {
            log.info("✅ data-quality 巡检通过,无越阈值项");
        } else {
            log.warn("{} count={}", ErrorCode.JOB_DATA_QUALITY_BREACHED.displayMessage(), breaches.size());
            for (String b : breaches) {
                log.warn("   - {}", b);
            }
        }
    }

    /** 真空样本独立于无效数值，避免把NaN当作一次正常跳过。 */
    private OptionalDouble calibrationEce(AdEventSource source) {
        var bins = inputs.loadCalibrationBins(source);
        if (bins.isEmpty()) {
            return OptionalDouble.empty();
        }
        long[] n = new long[bins.size()];
        double[] mean = new double[bins.size()];
        double[] actual = new double[bins.size()];
        long total = 0;
        for (int i = 0; i < bins.size(); i++) {
            var bin = bins.get(i);
            n[i] = bin.count();
            mean[i] = bin.meanPrediction();
            actual[i] = bin.actualRate();
            if (n[i] < 0 || !Double.isFinite(mean[i]) || !Double.isFinite(actual[i])) throw invalidInput();
            total = Math.addExact(total, n[i]);
        }
        if (total == 0) return OptionalDouble.empty();
        return measured(DataQuality.expectedCalibrationError(n, mean, actual));
    }

    /** 近窗或基线真空时明确缺数据，公式及原时间窗口不变。 */
    private OptionalDouble categoryPsi(BehaviorSource behavior, CatalogTable items, int days) {
        Map<String, Long> base = new LinkedHashMap<>();
        Map<String, Long> recent = new LinkedHashMap<>();
        // null只表示全历史；0/负days仍是原时间下界，不能当作关闭过滤。
        inputs.scanCategories(behavior, items, null, context -> {
            var row = context.getResultObject();
            if (row.count() <= 0) throw invalidInput();
            base.put(row.category(), row.count());
        });
        inputs.scanCategories(behavior, items, days, context -> {
            var row = context.getResultObject();
            if (row.count() <= 0) throw invalidInput();
            recent.put(row.category(), row.count());
        });
        if (base.isEmpty() || recent.isEmpty()) {
            return OptionalDouble.empty();
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
        return measured(DataQuality.psi(e, a));
    }

    private void writeReport(Map<String, String> report, List<String> breaches,
                             Map<DataQualityCheck, Status> states) throws IOException {
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
            // 追加状态而非改写既有指标和列，保留CSV读取协议及原可用数值。
            for (DataQualityCheck check : DataQualityCheck.values()) {
                w.write(check.metricKey() + "," + states.get(check).code());
                w.newLine();
            }
        }
        log.info("data-quality 报表已写入 {}", out.toAbsolutePath());
    }

    private static void failed(DataQualityCheck check, RuntimeException failure,
                               Map<DataQualityCheck, Status> states, List<RuntimeException> failures) {
        states.put(check, Status.FAILED);
        failures.add(failure);
        log.warn("{} check={} failureType={}", ErrorCode.JOB_DATA_QUALITY_CHECK_FAILED.displayMessage(),
                check.label(), failure.getClass().getSimpleName());
    }

    private static OptionalDouble measured(double value) {
        if (!Double.isFinite(value) || value < 0) throw invalidInput();
        return OptionalDouble.of(value);
    }

    private static PublicErrorException invalidInput() {
        return new PublicErrorException(ErrorCode.JOB_DATA_QUALITY_CHECK_FAILED);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static int intArg(ApplicationArguments a, String k, int def) {
        return JobArguments.integer(a, k, def);
    }

    private static double doubleArg(ApplicationArguments a, String k, double def) {
        return JobArguments.decimal(a, k, def);
    }
}
