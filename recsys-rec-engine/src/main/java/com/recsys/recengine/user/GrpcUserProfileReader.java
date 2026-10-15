package com.recsys.recengine.user;

import com.recsys.common.user.UserProfileReader;
import com.recsys.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(GrpcUserProfileReader.class);

    private final UserGateway userGateway;

    public GrpcUserProfileReader(UserGateway userGateway) {
        this.userGateway = userGateway;
    }

    /** 推荐允许用冷启动策略继续；该降级不再影响用户主动查询的真实性。 */
    @Override
    public List<String> categories(long userId) {
        try {
            return userGateway.getInterests(userId);
        } catch (RuntimeException failure) {
            log.warn("{}；推荐按冷启动降级，userId={} failureType={}",
                    ErrorCode.USER_INTEREST_QUERY_FAILED.displayMessage(), userId, failure.getClass().getSimpleName());
            return List.of();
        }
    }
}
