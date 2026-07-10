package com.recsys.common.user;

import java.util.List;

/**
 * 用户画像类目读取抽象(#3 user 上下文拆库):把"读 {@code app_user.profile->categories}"的来源与实现解耦,
 * 让 {@code TagRecaller}(recsys-recall)等消费方不直读用户上下文的 {@code app_user} 表。
 *
 * <p>两种来源,复用 {@code recsys.user.serving.mode}(P3 的 user 服务开关)一键切换/回滚:
 * <ul>
 *   <li>{@code in-process}(默认):{@code DbUserProfileReader}(recsys-recall)直读 {@code app_user}(今日行为)。</li>
 *   <li>{@code grpc}:{@code GrpcUserProfileReader}(recsys-rec-engine)经 gRPC 调 recsys-user-service
 *       (复用 P3 的 {@code GetInterests})—— app_user 物理搬到 user 自有库后,消费方不再跨库直读。</li>
 * </ul>
 * 口径与 {@code UserProfileService.getInterests} 一致(同读 {@code profile->'categories'})。
 */
public interface UserProfileReader {

    /** 用户偏好类目({@code app_user.profile->categories});无画像/失败返回空列表。 */
    List<String> categories(long userId);
}
