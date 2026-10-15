package com.recsys.console.alerts;

import com.recsys.console.diagnosis.DiagnosisReport;
import com.recsys.console.diagnosis.DiagnosisService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 告警复用诊断信号规则；失败和缺失保留可见状态，避免两份阈值及异常处理漂移。 */
@Service
public class AlertsService {
    private final DiagnosisService diagnosis;

    public AlertsService(DiagnosisService diagnosis) {
        this.diagnosis = diagnosis;
    }

    /** 仅投影原service/data-quality/latency来源，不因聚合复用扩大探测范围。 */
    public List<AlertItem> alerts() {
        DiagnosisReport snapshot = diagnosis.signalSnapshot();
        List<AlertItem> alerts = new ArrayList<>();
        for (DiagnosisReport.Check check : snapshot.checks()) {
            AlertItem.Source source = sourceOf(check.key());
            if (source == null) continue;
            AlertItem.Level level = switch (check.status()) {
                case PASS -> null;
                case FAIL -> AlertItem.Level.ERROR;
                case WARN -> AlertItem.Level.WARN;
                case INFO -> AlertItem.Level.INFO;
            };
            // 被动模块本来不参与在线告警；延迟无观测值仍可用INFO明确说明未知。
            if (level == null || (level == AlertItem.Level.INFO && source == AlertItem.Source.SERVICE)) continue;
            alerts.add(new AlertItem(level, source, check.name() + " · " + check.detail(), snapshot.checkedAt()));
        }
        alerts.sort(Comparator.comparingInt((AlertItem item) -> item.level().priority())
                .thenComparing(Comparator.comparingLong(AlertItem::ts).reversed()));
        return alerts;
    }

    private static AlertItem.Source sourceOf(String key) {
        if (key.startsWith(AlertItem.Source.SERVICE.code() + ":")) return AlertItem.Source.SERVICE;
        for (AlertItem.Source source : AlertItem.Source.values()) {
            if (source.code().equals(key)) return source;
        }
        return null;
    }
}
