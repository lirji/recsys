package com.recsys.recengine.user;

import com.recsys.proto.user.v1.Ack;
import com.recsys.proto.user.v1.Interests;
import com.recsys.proto.user.v1.UpdateInterestsRequest;
import com.recsys.proto.user.v1.UserId;
import com.recsys.proto.user.v1.UserProfileServiceGrpc;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 微服务实现:经 gRPC 调 {@code recsys-user-service}(用户画像在线服务独立进程)。
 * {@code recsys.user.serving.mode=grpc} 时才装配。目标地址见 {@code grpc.client.user.*}
 * (默认 {@code static://localhost:9097};接 Nacos discovery 后可换 {@code discovery:///recsys-user-service})。
 */
@Component
@ConditionalOnProperty(name = "recsys.user.serving.mode", havingValue = "grpc")
public class GrpcUserGateway implements UserGateway {

    private static final Logger log = LoggerFactory.getLogger(GrpcUserGateway.class);

    @GrpcClient("user")
    private UserProfileServiceGrpc.UserProfileServiceBlockingStub stub;

    // getInterests 是幂等读:@Retry 对瞬时错误重试,重试耗尽/非瞬时才落 fallback(按冷启动);@CircuitBreaker 内层无 fallback。
    // updateInterests 是非幂等写(下方),不挂 @Retry(避免重复写)。
    @Override
    @Retry(name = "user-grpc", fallbackMethod = "getInterestsFallback")
    @CircuitBreaker(name = "user-grpc")
    public List<String> getInterests(long userId) {
        Interests reply = stub.getInterests(UserId.newBuilder().setUserId(userId).build());
        return new ArrayList<>(reply.getCategoriesList());
    }

    @Override
    @CircuitBreaker(name = "user-grpc", fallbackMethod = "updateInterestsFallback")
    public void updateInterests(long userId, List<String> categories) {
        Ack ignored = stub.updateInterests(UpdateInterestsRequest.newBuilder()
                .setUserId(userId)
                .addAllCategories(categories == null ? List.of() : categories)
                .build());
    }

    /** 读兴趣降级(P1):user-service 不可达时返回空(视为冷启动),推荐照常走冷启动兜底。 */
    List<String> getInterestsFallback(long userId, Throwable t) {
        log.warn("user gRPC getInterests 降级为空(按冷启动处理): userId={} err={}", userId, t.toString());
        return List.of();
    }

    /** 写兴趣降级(P1):记日志不阻断(onboarding 写丢失不影响主链路)。 */
    void updateInterestsFallback(long userId, List<String> categories, Throwable t) {
        log.warn("user gRPC updateInterests 降级(兴趣写未落库): userId={} err={}", userId, t.toString());
    }
}
