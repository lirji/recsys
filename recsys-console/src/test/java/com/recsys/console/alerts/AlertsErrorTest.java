package com.recsys.console.alerts;

import com.recsys.common.error.ErrorCode;
import com.recsys.console.diagnosis.DiagnosisService;
import com.recsys.console.report.ReportService;
import com.recsys.console.report.ReportTable;
import com.recsys.console.system.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 用真实诊断聚合验证告警来源/等级/错误可见性，信号替身不证明真实依赖可用性。 */
class AlertsErrorTest {
    @Test void healthySignalsStayEmptyWithoutReadingEvaluationReports() {
        var fixture = new Fixture();
        assertTrue(fixture.service.alerts().isEmpty());
        verify(fixture.reports, times(1)).latest("data-quality");
        verify(fixture.reports, never()).latest("eval");
        verify(fixture.health, times(1)).health();
        verify(fixture.metrics, times(1)).metrics();
    }

    @ParameterizedTest
    @ValueSource(strings = {"service", "data-quality", "latency"})
    void failuresProduceSafeWarningsInsteadOfAnEmptyHealthyList(String source) throws Exception {
        var fixture = new Fixture();
        var failure = new IllegalStateException("private SQL password");
        switch (source) {
            case "service" -> when(fixture.health.health()).thenThrow(failure);
            case "latency" -> when(fixture.metrics.metrics()).thenThrow(failure);
            default -> when(fixture.reports.latest("data-quality")).thenThrow(failure);
        }
        var rows = fixture.service.alerts();
        assertEquals(1, rows.size());
        assertEquals(AlertItem.Level.WARN, rows.getFirst().level());
        assertEquals(source, rows.getFirst().source().code());
        assertTrue(rows.getFirst().message().contains(ErrorCode.CONSOLE_DIAGNOSIS_CHECK_FAILED.displayMessage()));
        String body = MockMvcBuilders.standaloneSetup(new AlertsController(fixture.service)).build()
                .perform(get("/api/console/alerts")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].level").value("WARN"))
                .andExpect(jsonPath("$[0].source").value(source)).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private"));
        verify(fixture.reports, never()).latest("eval");
    }

    @Test void severityOrderAndProtocolCodesStayStable() throws Exception {
        var fixture = new Fixture();
        when(fixture.health.health()).thenReturn(List.of(health("UNKNOWN", "app"), health("DOWN", "app"), health("JOB_ONLY", "job")));
        when(fixture.metrics.metrics()).thenReturn(new SystemMetrics(true, "prometheus", "none", null, null, null, null, 1));
        var rows = fixture.service.alerts();
        assertEquals(List.of(AlertItem.Level.ERROR, AlertItem.Level.WARN, AlertItem.Level.INFO), rows.stream().map(AlertItem::level).toList());
        assertEquals(1, rows.stream().map(AlertItem::ts).distinct().count());
        MockMvcBuilders.standaloneSetup(new AlertsController(fixture.service)).build()
                .perform(get("/api/console/alerts")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].level").value("ERROR"))
                .andExpect(jsonPath("$[1].level").value("WARN"))
                .andExpect(jsonPath("$[2].level").value("INFO"));
    }

    @Test void missingSignalsAndOldFreeTextBreachesNeverDisappearOrLeak() {
        var fixture = new Fixture();
        when(fixture.health.health()).thenReturn(List.of());
        when(fixture.reports.latest("data-quality")).thenReturn(null);
        when(fixture.metrics.metrics()).thenReturn(new SystemMetrics(false, "unavailable", "private", null, null, null, null, 1));
        var rows = fixture.service.alerts();
        assertEquals(3, rows.size());
        assertTrue(rows.stream().allMatch(a -> a.level() == AlertItem.Level.WARN));
        assertFalse(rows.toString().contains("private"));
        var quality = goodQuality();
        var oldRows = new ArrayList<>(quality.rows());
        oldRows.add(List.of("breach", "private SQL password"));
        when(fixture.reports.latest("data-quality")).thenReturn(new ReportTable(quality.fileName(), quality.category(), quality.columns(), oldRows));
        var restored = fixture.service.alerts();
        assertTrue(restored.stream().filter(a -> a.source() == AlertItem.Source.DATA_QUALITY).findFirst().orElseThrow().message().contains("1 项越阈值"));
        assertFalse(restored.toString().contains("private"));
    }

    @Test void failedCategoryCheckIsVisibleEvenWhenOldMetricsAndBreachesAreHealthy() {
        var fixture=new Fixture();var original=goodQuality();var rows=new ArrayList<>(original.rows());
        rows.removeIf(row->row.get(0).equals("check_category_status"));rows.add(List.of("check_category_status","FAILED"));
        when(fixture.reports.latest("data-quality")).thenReturn(new ReportTable(original.fileName(),original.category(),original.columns(),rows));
        var alerts=fixture.service.alerts();assertEquals(1,alerts.size());assertEquals(AlertItem.Source.DATA_QUALITY,alerts.getFirst().source());
        assertTrue(alerts.getFirst().message().contains(ErrorCode.JOB_DATA_QUALITY_CHECK_FAILED.displayMessage()));
    }

    private static ReportTable goodQuality() {
        return new ReportTable("data-quality.csv", "data-quality", List.of("metric", "value"),
                List.of(List.of("breaches", "0"), List.of("item_embedding_coverage", "1"),
                        List.of("user_embedding_coverage", "1"), List.of("pctr_ece", "0.01"), List.of("category_psi_7d", "0.02"),
                        List.of("check_embedding_status", "PASSED"), List.of("check_calibration_status", "PASSED"), List.of("check_category_status", "PASSED")));
    }

    private static SystemOverview.ServiceHealth health(String status, String kind) {
        return new SystemOverview.ServiceHealth("engine", "engine", kind, "http://engine", status, "controlled", 1);
    }

    private static class Fixture {
        final SystemHealthService health = mock(SystemHealthService.class);
        final SystemMetricsService metrics = mock(SystemMetricsService.class);
        final ReportService reports = mock(ReportService.class);
        final AlertsService service = new AlertsService(new DiagnosisService(health, metrics, reports));
        Fixture() {
            when(health.health()).thenReturn(List.of(health("UP", "app"), health("JOB_ONLY", "job")));
            when(metrics.metrics()).thenReturn(new SystemMetrics(true, "prometheus", "ready", 10.0, 5.0, 1.0, 2.0, 1));
            when(reports.latest("data-quality")).thenReturn(goodQuality());
        }
    }
}
