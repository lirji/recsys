package com.recsys.console.diagnosis;

import com.recsys.common.error.ErrorCode;
import com.recsys.console.report.MetricTable;
import com.recsys.console.report.ReportService;
import com.recsys.console.report.ReportTable;
import com.recsys.console.system.SystemHealthService;
import com.recsys.console.system.SystemMetrics;
import com.recsys.console.system.SystemMetricsService;
import com.recsys.console.system.SystemOverview.ServiceHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一键诊断:纯复用既有信号(SystemHealthService / SystemMetricsService / ReportService)组装体检清单,
 * 不新增探针、不写任何存储。每项检查独立 try/catch,单项失败不拖垮整份诊断。
 */
@Service
public class DiagnosisService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisService.class);
    private static final double P99_WARN_MS = 500.0;
    private static final double MIN_COVERAGE = 0.95;
    private static final double MAX_ECE = 0.05;

    private final SystemHealthService healthService;
    private final SystemMetricsService metricsService;
    private final ReportService reportService;

    public DiagnosisService(SystemHealthService healthService, SystemMetricsService metricsService,
                            ReportService reportService) {
        this.healthService = healthService;
        this.metricsService = metricsService;
        this.reportService = reportService;
    }

    /** 汇总已取得的证据，缺失或失败的关键检查不能用正常默认值补齐。 */
    public DiagnosisReport diagnose() {
        long now = Instant.now().toEpochMilli();
        List<DiagnosisReport.Check> checks = new ArrayList<>();

        checks.addAll(serviceChecks());
        checks.add(dataQualityCheck());
        checks.add(evalCheck());
        checks.add(latencyCheck());

        return new DiagnosisReport(worst(checks), checks, now);
    }

    // ---------- 服务健康 ----------

    private List<DiagnosisReport.Check> serviceChecks() {
        List<DiagnosisReport.Check> out = new ArrayList<>();
        try {
            for (ServiceHealth h : healthService.health()) {
                String status;
                if ("app".equalsIgnoreCase(h.kind())) {
                    // 在线服务:UP=PASS、DOWN=FAIL、其余(UNKNOWN)=WARN
                    status = "UP".equalsIgnoreCase(h.status()) ? "PASS"
                            : ("DOWN".equalsIgnoreCase(h.status()) ? "FAIL" : "WARN");
                } else {
                    // 库/被动目标非在线服务,给 INFO(不影响 overall 的 FAIL 判定)
                    status = "INFO";
                }
                out.add(new DiagnosisReport.Check(
                        "service:" + h.service(), h.name() + " · 健康", status,
                        h.status() + " · " + (h.message() == null ? "" : h.message())));
            }
        } catch (Exception e) {
            out.add(failedCheck("service", "服务健康", e));
        }
        if (out.isEmpty()) out.add(new DiagnosisReport.Check("service", "服务健康", "WARN",
                ErrorCode.CONSOLE_DIAGNOSIS_INCOMPLETE.displayMessage()));
        return out;
    }

    // ---------- 数据质量 ----------

    private DiagnosisReport.Check dataQualityCheck() {
        try {
            ReportTable dq = reportService.latest("data-quality");
            if (dq == null) {
                return new DiagnosisReport.Check("data-quality", "数据质量巡检", "WARN", ErrorCode.CONSOLE_DIAGNOSIS_INCOMPLETE.displayMessage());
            }
            MetricTable mt = MetricTable.of(dq);
            double breaches = mt.number("breaches", Double.NaN);
            double itemCov = mt.number("item_embedding_coverage", Double.NaN);
            double userCov = mt.number("user_embedding_coverage", Double.NaN);
            double ece = mt.number("pctr_ece", Double.NaN);
            if (!Double.isFinite(breaches) || breaches < 0 || breaches != Math.rint(breaches)
                    || !ratio(itemCov) || !ratio(userCov) || !ratio(ece)) {
                return new DiagnosisReport.Check("data-quality", "数据质量巡检", "WARN",
                        ErrorCode.CONSOLE_DIAGNOSIS_INCOMPLETE.displayMessage());
            }
            String status = "PASS";
            List<String> reasons = new ArrayList<>();
            if (breaches > 0 || !mt.breaches().isEmpty()) {
                status = "WARN";
                // 报表的自由文本可能来自旧作业异常，只呈现安全计数与下面的受控数值说明。
                reasons.add(String.format("报表记录 %.0f 项越阈值，请检查对应指标", Math.max(breaches, mt.breaches().size())));
            }
            if (itemCov < MIN_COVERAGE || userCov < MIN_COVERAGE) {
                status = "WARN";
                reasons.add(String.format("embedding 覆盖率偏低(item %.3f / user %.3f)", itemCov, userCov));
            }
            if (ece > MAX_ECE) {
                status = "WARN";
                if (reasons.isEmpty()) {
                    reasons.add(String.format("pCTR ECE %.3f > %.2f", ece, MAX_ECE));
                }
            }
            String detail = reasons.isEmpty() ? "已取得的覆盖率/ECE及越阈值计数未见异常" : String.join(" · ", reasons);
            return new DiagnosisReport.Check("data-quality", "数据质量巡检", status, detail);
        } catch (Exception e) {
            return failedCheck("data-quality", "数据质量巡检", e);
        }
    }

    // ---------- 离线评估存在性 ----------

    private DiagnosisReport.Check evalCheck() {
        try {
            ReportTable eval = reportService.latest("eval");
            return eval != null
                    ? new DiagnosisReport.Check("eval", "离线评估", "PASS", "最新评估报表 " + eval.fileName())
                    : new DiagnosisReport.Check("eval", "离线评估", "WARN", ErrorCode.CONSOLE_DIAGNOSIS_INCOMPLETE.displayMessage());
        } catch (Exception e) {
            return failedCheck("eval", "离线评估", e);
        }
    }

    // ---------- 链路延迟 ----------

    private DiagnosisReport.Check latencyCheck() {
        try {
            SystemMetrics m = metricsService.metrics();
            if (m.available() && m.recommendP99Ms() != null) {
                String status = m.recommendP99Ms() > P99_WARN_MS ? "WARN" : "PASS";
                return new DiagnosisReport.Check("latency", "推荐链路延迟", status,
                        String.format("P99 %.0fms(阈值 %.0fms)", m.recommendP99Ms(), P99_WARN_MS));
            }
            return m.available()
                    ? new DiagnosisReport.Check("latency", "推荐链路延迟", "INFO", "当前窗口无可用延迟指标")
                    : new DiagnosisReport.Check("latency", "推荐链路延迟", "WARN", ErrorCode.CONSOLE_METRICS_UNAVAILABLE.displayMessage());
        } catch (Exception e) {
            return failedCheck("latency", "推荐链路延迟", e);
        }
    }

    private static boolean ratio(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }

    /** 不把内部异常、URL或SQL写入正常诊断对象；失败显式影响overall。 */
    private static DiagnosisReport.Check failedCheck(String key, String name, Exception failure) {
        log.debug("{} check={} failureType={}", ErrorCode.CONSOLE_DIAGNOSIS_CHECK_FAILED.displayMessage(),
                key, failure.getClass().getSimpleName());
        return new DiagnosisReport.Check(key, name, "WARN", ErrorCode.CONSOLE_DIAGNOSIS_CHECK_FAILED.displayMessage());
    }

    // ---------- 汇总 ----------

    private static String worst(List<DiagnosisReport.Check> checks) {
        boolean warn = false;
        for (DiagnosisReport.Check c : checks) {
            if ("FAIL".equals(c.status())) {
                return "FAIL";
            }
            if ("WARN".equals(c.status())) {
                warn = true;
            }
        }
        return warn ? "WARN" : "PASS";
    }
}
