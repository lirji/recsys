package com.recsys.recengine;

import com.recsys.common.dto.RecommendRequest;
import com.recsys.common.dto.RecommendResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 推荐 / 搜索主入口。
 * <ul>
 *   <li>GET /api/recommend?userId=&size=&scene=[&q=] —— 纯推荐(可选带 q 即转 query 驱动)</li>
 *   <li>GET /api/search?q=&userId=&size= —— 搜索:query 驱动召回(SEMANTIC + 意图 TAG),见 docs/05</li>
 * </ul>
 */
@RestController
public class RecommendController {

    private static final String SEARCH_SCENE = "search";

    private final RecommendOrchestrator orchestrator;

    public RecommendController(RecommendOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping("/api/recommend")
    public RecommendResponse recommend(
            @RequestParam long userId,
            @RequestParam(required = false, defaultValue = "0") int size,
            @RequestParam(required = false) String scene,
            @RequestParam(required = false) String q) {
        return orchestrator.recommend(new RecommendRequest(userId, size, scene, q));
    }

    @GetMapping("/api/search")
    public RecommendResponse search(
            @RequestParam("q") String q,
            @RequestParam(required = false, defaultValue = "0") long userId,
            @RequestParam(required = false, defaultValue = "0") int size) {
        return orchestrator.recommend(new RecommendRequest(userId, size, SEARCH_SCENE, q));
    }
}
