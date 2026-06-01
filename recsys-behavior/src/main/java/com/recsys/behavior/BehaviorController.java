package com.recsys.behavior;

import com.recsys.common.dto.BehaviorEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 行为采集入口(Track E · E1)。架构文档 §5.6 POST /api/behavior。
 * 前端推荐卡片点击/曝光按 BehaviorEvent 契约上报到此。
 */
@RestController
@RequestMapping("/api/behavior")
public class BehaviorController {

    private static final Logger log = LoggerFactory.getLogger(BehaviorController.class);

    private final BehaviorService service;

    public BehaviorController(BehaviorService service) {
        this.service = service;
    }

    /** 单条上报。 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> report(@RequestBody BehaviorEvent event) {
        service.record(event);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 批量上报(前端可攒批降低请求数)。 */
    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> reportBatch(@RequestBody List<BehaviorEvent> events) {
        if (events != null) {
            for (BehaviorEvent e : events) {
                service.record(e);
            }
        }
        int n = events == null ? 0 : events.size();
        return ResponseEntity.ok(Map.of("ok", true, "count", n));
    }
}
