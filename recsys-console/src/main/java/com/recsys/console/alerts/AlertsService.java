package com.recsys.console.alerts;

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
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 告警面板:从既有信号(服务健康 / 数据质量报表 / 链路延迟)派生一份当前告警列表,复用现有服务,
 * 不新增探针、不落库。每来源独立 try/catch;按 level(ERROR→WARN→INFO)再 ts 倒序。
 */
@Service
public class AlertsService {

    private static final Logger log = LoggerFactory.getLogger(AlertsService.class);
    private static final double P99_WARN_MS = 500.0;
    private static final Map<String, Integer> LEVEL_ORDER = Map.of("ERROR", 0, "WARN", 1, "INFO", 2);

    private final SystemHealthService healthService;
    private final SystemMetricsService metricsService;
    private final ReportService reportService;

    public AlertsService(SystemHealthService healthService, SystemMetricsService metricsService,
                         ReportService reportService) {
        this.healthService = healthService;
        this.metricsService = metricsService;
        this.reportService = reportService;
    }

    public List<AlertItem> alerts() {
        long now = Instant.now().toEpochMilli();
        List<AlertItem> out = new ArrayList<>();

        // 服务健康:app 服务 DOWN=ERROR、UNKNOWN=WARN
        try {
            for (ServiceHealth h : healthService.health()) {
                if (!"app".equalsIgnoreCase(h.kind())) {
                    continue;
                }
                if ("DOWN".equalsIgnoreCase(h.status())) {
                    out.add(new AlertItem("ERROR", "service", h.name() + " 不可达(DOWN)", now));
                } else if (!"UP".equalsIgnoreCase(h.status())) {
                    out.add(new AlertItem("WARN", "service", h.name() + " 状态未知(" + h.status() + ")", now));
                }
            }
        } catch (Exception e) {
            log.debug("告警-服务健康失败: {}", e.getMessage());
        }

        // 数据质量:最新报表的每条 breach → WARN
        try {
            ReportTable dq = reportService.latest("data-quality");
            if (dq != null) {
                for (String breach : MetricTable.of(dq).breaches()) {
                    out.add(new AlertItem("WARN", "data-quality", breach, now));
                }
            }
        } catch (Exception e) {
            log.debug("告警-数据质量失败: {}", e.getMessage());
        }

        // 链路延迟:推荐 P99 越界 → WARN
        try {
            SystemMetrics m = metricsService.metrics();
            if (m.available() && m.recommendP99Ms() != null && m.recommendP99Ms() > P99_WARN_MS) {
                out.add(new AlertItem("WARN", "latency",
                        String.format("推荐链路 P99 %.0fms 超阈值 %.0fms", m.recommendP99Ms(), P99_WARN_MS), now));
            }
        } catch (Exception e) {
            log.debug("告警-延迟失败: {}", e.getMessage());
        }

        out.sort(Comparator.comparingInt((AlertItem a) -> LEVEL_ORDER.getOrDefault(a.level(), 3))
                .thenComparing(Comparator.comparingLong(AlertItem::ts).reversed()));
        return out;
    }
}
