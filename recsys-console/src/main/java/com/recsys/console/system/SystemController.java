package com.recsys.console.system;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.recsys.console.system.SystemOverview.ApiEndpoint;
import static com.recsys.console.system.SystemOverview.CommandGroup;
import static com.recsys.console.system.SystemOverview.ModuleInfo;
import static com.recsys.console.system.SystemOverview.ServiceHealth;

@RestController
@RequestMapping("/api/console/system")
public class SystemController {

    private final SystemOverviewService service;
    private final SystemHealthService healthService;
    private final SystemMetricsService metricsService;

    public SystemController(SystemOverviewService service, SystemHealthService healthService,
                            SystemMetricsService metricsService) {
        this.service = service;
        this.healthService = healthService;
        this.metricsService = metricsService;
    }

    @GetMapping("/overview")
    public SystemOverview overview() {
        return service.overview();
    }

    @GetMapping("/modules")
    public List<ModuleInfo> modules() {
        return service.modules();
    }

    @GetMapping("/health")
    public List<ServiceHealth> health() {
        return healthService.health();
    }

    @GetMapping("/metrics")
    public SystemMetrics metrics() {
        return metricsService.metrics();
    }

    @GetMapping("/apis")
    public List<ApiEndpoint> apis() {
        return service.apis();
    }

    @GetMapping("/commands")
    public List<CommandGroup> commands() {
        return service.commands();
    }
}
