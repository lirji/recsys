package com.recsys.console.alerts;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 告警面板只读端点。走 /api/console/**(网关已路由到 console:8090)。 */
@RestController
public class AlertsController {

    private final AlertsService service;

    public AlertsController(AlertsService service) {
        this.service = service;
    }

    @GetMapping("/api/console/alerts")
    public List<AlertItem> alerts() {
        return service.alerts();
    }
}
