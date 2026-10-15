package com.recsys.console.diagnosis;

import com.recsys.common.error.ErrorCode;
import com.recsys.console.report.ReportService;
import com.recsys.console.report.ReportTable;
import com.recsys.console.system.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static com.recsys.console.diagnosis.DiagnosisReport.State.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 只验证诊断聚合与公开HTTP，信号源用替身；真实HTTP观测另由SystemObservationHttpTest验证。 */
class DiagnosisErrorTest {
    @Test void completeHealthySignalsKeepPassAndThresholdBreachesRemainWarn() {
        var fixture = new Fixture();
        assertEquals(PASS, fixture.service.diagnose().overall());
        when(fixture.reports.latest("data-quality")).thenReturn(quality("item_embedding_coverage", "0.5"));
        assertEquals(WARN, fixture.service.diagnose().overall());
        assertTrue(check(fixture, "data-quality").detail().contains("覆盖率偏低"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"service", "data-quality", "eval", "latency"})
    void failedCheckCannotLeakExceptionOrCountAsPass(String key) throws Exception {
        var fixture = new Fixture();
        var failure = new IllegalStateException("private SQL jdbc://secret:password@localhost");
        switch (key) {
            case "service" -> when(fixture.health.health()).thenThrow(failure);
            case "latency" -> when(fixture.metrics.metrics()).thenThrow(failure);
            default -> when(fixture.reports.latest(key)).thenThrow(failure);
        }
        var check = check(fixture, key);
        assertEquals(WARN, check.status());
        assertEquals(ErrorCode.CONSOLE_DIAGNOSIS_CHECK_FAILED.displayMessage(), check.detail());
        var body = MockMvcBuilders.standaloneSetup(new DiagnosisController(fixture.service)).build()
                .perform(get("/api/console/diagnosis")).andExpect(status().isOk())
                .andExpect(jsonPath("$.overall").value("WARN")).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private")); assertFalse(body.contains("password"));
    }

    @ParameterizedTest
    @CsvSource({"breaches,NaN", "breaches,-1", "breaches,0.5", "item_embedding_coverage,bad",
            "item_embedding_coverage,Infinity", "item_embedding_coverage,-0.1", "user_embedding_coverage,1.1", "pctr_ece,NaN", "category_psi_7d,NaN", "category_psi_7d,-1"})
    void invalidQualityValuesAreUnknownInsteadOfPassingDefaults(String metric, String value) {
        var fixture = new Fixture();
        when(fixture.reports.latest("data-quality")).thenReturn(quality(metric, value));
        assertEquals(WARN, fixture.service.diagnose().overall());
        assertEquals(ErrorCode.CONSOLE_DIAGNOSIS_INCOMPLETE.displayMessage(), check(fixture, "data-quality").detail());
    }

    @Test void missingQualityColumnsDoNotCountAsFullyHealthy() {
        var fixture = new Fixture();
        when(fixture.reports.latest("data-quality")).thenReturn(new ReportTable("quality.csv", "data-quality", List.of("wrong"), List.of()));
        assertEquals(WARN, check(fixture, "data-quality").status());
        assertEquals(ErrorCode.JOB_DATA_QUALITY_INCOMPLETE.displayMessage(), check(fixture, "data-quality").detail());
    }

    @Test void freeTextBreachesDoNotLeakFromOldReports() {
        var fixture = new Fixture();
        var good = quality(null, null);
        var rows = new ArrayList<>(good.rows());
        rows.add(List.of("breach", "private SQL password"));
        when(fixture.reports.latest("data-quality")).thenReturn(new ReportTable(good.fileName(), good.category(), good.columns(), rows));
        var check = check(fixture, "data-quality");
        assertEquals(WARN, check.status());
        assertTrue(check.detail().contains("1 项越阈值"));
        assertFalse(check.detail().contains("private"));
    }

    @Test void unavailableMetricsAndEmptyTargetsAreNotOverallPass() {
        var fixture = new Fixture();
        when(fixture.metrics.metrics()).thenReturn(new SystemMetrics(false, "unavailable", "private upstream", null, null, null, null, 1));
        assertEquals(WARN, fixture.service.diagnose().overall());
        assertEquals(ErrorCode.CONSOLE_METRICS_UNAVAILABLE.displayMessage(), check(fixture, "latency").detail());
        when(fixture.metrics.metrics()).thenReturn(new SystemMetrics(true, "prometheus", "none", null, null, null, null, 1));
        assertEquals(INFO, check(fixture, "latency").status());
        when(fixture.health.health()).thenReturn(List.of());
        assertEquals(WARN, fixture.service.diagnose().overall());
        assertEquals(ErrorCode.CONSOLE_DIAGNOSIS_INCOMPLETE.displayMessage(), check(fixture, "service").detail());
    }

    @ParameterizedTest
    @ValueSource(strings={"FAILED","NO_DATA","private SQL password","MISSING","LEGACY","BREACHED"})
    void producerStatusCannotBeHiddenByZeroBreachesAndHealthyNumericValues(String status) throws Exception {
        var fixture=new Fixture();var original=quality(null,null);var rows=new ArrayList<>(original.rows());
        if(status.equals("LEGACY")) rows.removeIf(row->row.get(0).startsWith("check_"));
        else {
            rows.removeIf(row->row.get(0).equals("check_category_status"));
            if(!status.equals("MISSING")) rows.add(List.of("check_category_status",status));
        }
        when(fixture.reports.latest("data-quality")).thenReturn(new ReportTable(original.fileName(),original.category(),original.columns(),rows));
        ErrorCode expected=switch(status){case "FAILED"->ErrorCode.JOB_DATA_QUALITY_CHECK_FAILED;case "BREACHED"->ErrorCode.JOB_DATA_QUALITY_BREACHED;default->ErrorCode.JOB_DATA_QUALITY_INCOMPLETE;};
        assertEquals(WARN,check(fixture,"data-quality").status());assertEquals(expected.displayMessage(),check(fixture,"data-quality").detail());
        String body=MockMvcBuilders.standaloneSetup(new DiagnosisController(fixture.service)).build()
                .perform(get("/api/console/diagnosis")).andExpect(status().isOk()).andExpect(jsonPath("$.overall").value("WARN"))
                .andReturn().getResponse().getContentAsString();assertFalse(body.contains("private"));
    }

    @Test void passedCategoryStateStillRequiresOneUsablePsiMetric() {
        var fixture=new Fixture();var original=quality(null,null);var rows=new ArrayList<>(original.rows());
        rows.removeIf(row->row.get(0).equals("category_psi_7d"));
        when(fixture.reports.latest("data-quality")).thenReturn(new ReportTable(original.fileName(),original.category(),original.columns(),rows));
        assertEquals(ErrorCode.CONSOLE_DIAGNOSIS_INCOMPLETE.displayMessage(),check(fixture,"data-quality").detail());
        rows.add(List.of("category_psi_7d","0.01"));rows.add(List.of("category_psi_0d","0.02"));
        assertEquals(WARN,check(fixture,"data-quality").status());
    }

    private static DiagnosisReport.Check check(Fixture fixture, String key) {
        return fixture.service.diagnose().checks().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }

    private static ReportTable quality(String metric, String value) {
        var rows = new ArrayList<List<String>>();
        for (var row : List.of(List.of("breaches", "0"), List.of("item_embedding_coverage", "1"),
                List.of("user_embedding_coverage", "1"), List.of("pctr_ece", "0.01"), List.of("category_psi_7d", "0.02"))) {
            rows.add(row.get(0).equals(metric) ? List.of(metric, value) : row);
        }
        for(var check:com.recsys.common.report.DataQualityCheck.values()) rows.add(List.of(check.metricKey(),"PASSED"));
        return new ReportTable("data-quality.csv", "data-quality", List.of("metric", "value"), rows);
    }

    private static class Fixture {
        final SystemHealthService health = mock(SystemHealthService.class);
        final SystemMetricsService metrics = mock(SystemMetricsService.class);
        final ReportService reports = mock(ReportService.class);
        final DiagnosisService service = new DiagnosisService(health, metrics, reports);
        Fixture() {
            when(health.health()).thenReturn(List.of(new SystemOverview.ServiceHealth("engine", "engine", "app", "http://engine", "UP", "reachable", 1)));
            when(metrics.metrics()).thenReturn(new SystemMetrics(true, "prometheus", "ready", 10.0, 5.0, 1.0, 2.0, 1));
            when(reports.latest("data-quality")).thenReturn(quality(null, null));
            when(reports.latest("eval")).thenReturn(new ReportTable("metrics.csv", "eval", List.of(), List.of()));
        }
    }
}
