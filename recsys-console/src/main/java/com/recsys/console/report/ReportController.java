package com.recsys.console.report;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 离线报表读取端点(控制台离线可视化的数据源)。
 * 走 /api/console/** —— 网关 /** 兜底到 web:8090,无需改网关路由。
 */
@RestController
@RequestMapping("/api/console/report")
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    /** 列出 eval 目录下所有报表 CSV(按分类 + 时间倒序)。 */
    @GetMapping("/index")
    public List<ReportFileInfo> index() {
        return service.list();
    }

    /** 读取单个报表文件,解析为 {columns, rows}。 */
    @GetMapping("/file")
    public ReportTable file(@RequestParam String name) {
        return service.read(name);
    }
}
