package com.recsys.recengine.user;

import com.recsys.proto.user.v1.Ack;
import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
import io.grpc.StatusRuntimeException;
import com.recsys.proto.grpc.GrpcErrors;
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
        Interests reply = GrpcErrors.call(ErrorCode.RPC_USER_QUERY_FAILED,
                () -> stub.getInterests(UserId.newBuilder().setUserId(userId).build()));
        return new ArrayList<>(reply.getCategoriesList());
    }

    /** 保存结果必须有owner成功确认，不能由网络失败或熔断fallback伪造。 */
    @Override
    @CircuitBreaker(name = "user-grpc", fallbackMethod = "updateInterestsFallback")
    public void updateInterests(long userId, List<String> categories) {
        try {
            Ack reply = GrpcErrors.call(ErrorCode.RPC_USER_WRITE_RESULT_UNKNOWN, () -> stub.updateInterests(UpdateInterestsRequest.newBuilder()
                    .setUserId(userId)
                    .addAllCategories(categories == null ? List.of() : categories)
                    .build()));
            if (!reply.getOk()) throw new PublicErrorException(ErrorCode.USER_INTEREST_WRITE_RESULT_UNKNOWN);
        } catch (StatusRuntimeException failure) {
            // 不依赖fallback切面才暴露失败，直接调用同样不能返回保存成功。
            throw new PublicErrorException(ErrorCode.USER_INTEREST_WRITE_RESULT_UNKNOWN, failure);
        }
    }

    /** 读兴趣降级(P1):user-service 不可达时返回空(视为冷启动),推荐照常走冷启动兜底。 */
    List<String> getInterestsFallback(long userId, Throwable t) {
        log.warn("{}；降级为冷启动，userId={} failureType={}", ErrorCode.RPC_USER_QUERY_FAILED.displayMessage(), userId, t.getClass().getSimpleName());
        return List.of();
    }

    /** 写入未知不能继续返回ok=true；熔断快速拒绝也走同一安全错误。 */
    void updateInterestsFallback(long userId, List<String> categories, Throwable t) {
        log.warn("{} userId={} failureType={}", ErrorCode.RPC_USER_WRITE_RESULT_UNKNOWN.displayMessage(), userId, t.getClass().getSimpleName());
        throw t instanceof PublicErrorException known ? known : new PublicErrorException(ErrorCode.USER_INTEREST_WRITE_RESULT_UNKNOWN, t);
    }
}
