package com.recsys.common.dto;

/**
 * 推荐请求。对应 GET /api/recommend?userId=&size=&scene=
 *
 * @param userId 用户 ID
 * @param size   期望返回条数
 * @param scene  场景标识(如 feed / detail-related),用于多场景区分策略
 */
public record RecommendRequest(long userId, int size, String scene) {

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
}
