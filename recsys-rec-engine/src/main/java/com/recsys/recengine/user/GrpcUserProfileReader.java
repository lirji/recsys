package com.recsys.recengine.user;

import com.recsys.common.user.UserProfileReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * gRPC 用户画像类目读(#3):经 recsys-user-service 读 {@code app_user.profile->categories},
 * 让 {@code TagRecaller} 不直读用户库(app_user 物理拆到 user 自有库后仍可用)。
 *
 * <p>复用 P3 的 {@link UserGateway#getInterests}(gRPC {@code GetInterests} 就是读 profile 类目,口径一致),
 * 故无需新 RPC。仅 {@code recsys.user.serving.mode=grpc} 时装配(此时 UserGateway=GrpcUserGateway)。
 */
@Component
@ConditionalOnProperty(name = "recsys.user.serving.mode", havingValue = "grpc")
public class GrpcUserProfileReader implements UserProfileReader {

    private final UserGateway userGateway;

    public GrpcUserProfileReader(UserGateway userGateway) {
        this.userGateway = userGateway;
    }

    @Override
    public List<String> categories(long userId) {
        return userGateway.getInterests(userId);
    }
}
