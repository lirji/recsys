package com.recsys.recengine.user;

import java.util.List;

/**
 * 用户画像服务网关(P3,rec-engine 侧调用抽象)。屏蔽"进程内直调 {@code UserProfileService} vs 跨进程 gRPC 调
 * recsys-user-service"两种实现,供绞杀者迁移以 {@code recsys.user.serving.mode} 一键切换与回滚。
 * 承载兴趣 onboarding 读/写；gRPC模式的推荐类目复用此入口，由GrpcUserProfileReader决定冷启动降级。
 */
public interface UserGateway {

    /** 严格读取偏好；无画像返回空，失败交调用边界处理，不能视为画像不存在。 */
    List<String> getInterests(long userId);

    /** 设置(覆盖)用户偏好类目。 */
    void updateInterests(long userId, List<String> categories);
}
