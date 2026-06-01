package com.recsys.recengine;

import com.recsys.common.dto.RecommendRequest;
import com.recsys.common.dto.RecommendResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 推荐主入口。GET /api/recommend?userId=&size=&scene=
 */
@RestController
public class RecommendController {

    private final RecommendOrchestrator orchestrator;

    public RecommendController(RecommendOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping("/api/recommend")
    public RecommendResponse recommend(
            @RequestParam long userId,
            @RequestParam(required = false, defaultValue = "0") int size,
            @RequestParam(required = false) String scene) {
        return orchestrator.recommend(new RecommendRequest(userId, size, scene));
    }
}
