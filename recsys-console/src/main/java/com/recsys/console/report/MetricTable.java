package com.recsys.console.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把两列 {@code metric,value} 报表(如 data-quality)解析成查表 + breach 消息列表。
 * 供一键诊断 / 告警面板复用。找不到 metric/value 列则返回空表(优雅降级)。
 */
public final class MetricTable {

    private final Map<String, String> values;
    private final List<String> breaches;

    private MetricTable(Map<String, String> values, List<String> breaches) {
        this.values = values;
        this.breaches = breaches;
    }

    public static MetricTable of(ReportTable t) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> breaches = new ArrayList<>();
        if (t == null || t.columns() == null) {
            return new MetricTable(values, breaches);
        }
        int mi = t.columns().indexOf("metric");
        int vi = t.columns().indexOf("value");
        if (mi < 0 || vi < 0) {
            return new MetricTable(values, breaches);
        }
        for (List<String> row : t.rows()) {
            if (row.size() <= Math.max(mi, vi)) {
                continue;
            }
            String metric = row.get(mi);
            String value = row.get(vi);
            if ("breach".equals(metric)) {
                breaches.add(value);
            } else if (metric != null) {
                values.put(metric, value);
            }
        }
        return new MetricTable(values, breaches);
    }

    /** 取数值型 metric,缺失/非数字返回默认值。 */
    public double number(String metric, double dflt) {
        String v = values.get(metric);
        if (v == null || v.isBlank()) {
            return dflt;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** 所有 breach 行的人类可读消息。 */
    public List<String> breaches() {
        return breaches;
    }

    public Map<String, String> values() {
        return values;
    }
}
