package com.recsys.recengine.user;

import java.util.List;

/**
 * 用户画像服务网关(P3,rec-engine 侧调用抽象)。屏蔽"进程内直调 {@code UserProfileService} vs 跨进程 gRPC 调
 * recsys-user-service"两种实现,供绞杀者迁移以 {@code recsys.user.serving.mode} 一键切换与回滚。
 * 仅承载冷启动兴趣 onboarding 的读/写(每请求 O(1) 次);TAG 召回的逐候选类目读仍走进程内(读 app_user)。
 */
public interface UserGateway {

    /** 读用户偏好类目;无画像返回空列表。 */
    List<String> getInterests(long userId);

    /** 设置(覆盖)用户偏好类目。 */
    void updateInterests(long userId, List<String> categories);
}
