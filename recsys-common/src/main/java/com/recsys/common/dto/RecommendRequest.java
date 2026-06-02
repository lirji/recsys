package com.recsys.common.dto;

/**
 * 推荐 / 搜索请求。
 * 对应 GET /api/recommend?userId=&size=&scene= 与 GET /api/search?q=&userId=&size=。
 *
 * @param userId 用户 ID
 * @param size   期望返回条数
 * @param scene  场景标识(如 feed / detail-related / search),用于多场景区分策略
 * @param query  原始查询文本(搜索场景);为 null/空表示纯推荐(userId 驱动),
 *               非空则走 query 驱动:经 Query 理解层解析后驱动 SEMANTIC / TAG 召回(见 docs/05)
 */
public record RecommendRequest(long userId, int size, String scene, String query) {

    public static final String DEFAULT_SCENE = "feed";
    public static final int DEFAULT_SIZE = 10;

    public RecommendRequest {
        if (size <= 0) {
            size = DEFAULT_SIZE;
        }
        if (scene == null || scene.isBlank()) {
            scene = DEFAULT_SCENE;
        }
    }

    /** 兼容纯推荐调用:无 query。 */
    public RecommendRequest(long userId, int size, String scene) {
        this(userId, size, scene, null);
    }

    /** 是否为 query 驱动(搜索)请求。 */
    public boolean hasQuery() {
        return query != null && !query.isBlank();
    }
}
