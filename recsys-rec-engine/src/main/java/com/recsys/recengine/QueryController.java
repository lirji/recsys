package com.recsys.recengine;

import com.recsys.common.query.QueryUnderstandingService;
import com.recsys.common.query.StructuredQuery;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Query 理解层调试入口:GET /api/query/parse?q=&userId=
 *
 * <p>把原始查询解析为 {@link StructuredQuery},用于单独验证 Query 层
 * (归一化 / 分词 / 意图 / 向量化降级)。后续搜索 / 搜索广告链路会复用同一
 * {@link QueryUnderstandingService},把结果喂给召回(SEMANTIC 读 query、
 * TAG 读意图类目)与相关性打分(见 docs/05)。
 */
@RestController
public class QueryController {

    private final QueryUnderstandingService queryService;

    public QueryController(QueryUnderstandingService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/api/query/parse")
    public StructuredQuery parse(
            @RequestParam("q") String q,
            @RequestParam(required = false, defaultValue = "0") long userId) {
        return queryService.parse(q, userId);
    }
}
