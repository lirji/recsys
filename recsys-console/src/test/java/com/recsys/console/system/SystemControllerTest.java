package com.recsys.console.system;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SystemControllerTest {

    @Test
    void exposesOverviewModulesApisCommandsAndHealth() {
        SystemOverviewService overviewService = new SystemOverviewService();
        SystemHealthProperties properties = new SystemHealthProperties();
        SystemHealthProperties.Target target = new SystemHealthProperties.Target();
        target.setService("recsys-offline");
        target.setName("离线作业");
        target.setKind("job");
        target.setPassiveStatus("JOB_ONLY");
        target.setPassiveMessage("按需运行");
        properties.setTargets(List.of(target));
        SystemHealthService healthService = new SystemHealthService(properties);
        SystemMetricsProperties metricsProperties = new SystemMetricsProperties();
        SystemMetricsService metricsService = new SystemMetricsService(metricsProperties);
        SystemController controller = new SystemController(overviewService, healthService, metricsService);

        assertThat(controller.overview().projectName()).isEqualTo("recsys");
        assertThat(controller.modules()).isNotEmpty();
        assertThat(controller.apis()).isNotEmpty();
        assertThat(controller.commands()).isNotEmpty();
        assertThat(controller.health()).singleElement()
                .extracting(SystemOverview.ServiceHealth::status)
                .isEqualTo("JOB_ONLY");
    }

    @Test
    void metricsDegradeGracefullyWhenDisabled() {
        // 观测栈未起 / 关闭时,/metrics 不外呼、优雅降级为 unavailable。
        SystemMetricsProperties props = new SystemMetricsProperties();
        props.setEnabled(false);
        SystemController controller = new SystemController(
                new SystemOverviewService(),
                new SystemHealthService(new SystemHealthProperties()),
                new SystemMetricsService(props));

        SystemMetrics metrics = controller.metrics();
        assertThat(metrics.available()).isFalse();
        assertThat(metrics.recommendP99Ms()).isNull();
    }
}
