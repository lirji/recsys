package com.recsys.console.diagnosis;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 一键诊断只读端点。走 /api/console/**(网关已路由到 console:8090)。 */
@RestController
public class DiagnosisController {

    private final DiagnosisService service;

    public DiagnosisController(DiagnosisService service) {
        this.service = service;
    }

    @GetMapping("/api/console/diagnosis")
    public DiagnosisReport diagnose() {
        return service.diagnose();
    }
}
