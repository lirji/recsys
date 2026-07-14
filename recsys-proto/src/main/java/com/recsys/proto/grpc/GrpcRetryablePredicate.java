package com.recsys.proto.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.util.function.Predicate;

/**
 * gRPC 重试判定(P1 弹性)。仅对<b>瞬时</b>错误重试:{@code UNAVAILABLE}(下游未就绪 / 连接被拒 / 实例摘除)、
 * {@code DEADLINE_EXCEEDED}(单次调用超时)。业务 / 永久错误(UNAUTHENTICATED / INVALID_ARGUMENT / NOT_FOUND …)
 * 与熔断快速失败({@link io.github.resilience4j.circuitbreaker.CallNotPermittedException},非 gRPC 异常)一律不重试——
 * 避免放大故障、避免对非幂等副作用重复触发。
 *
 * <p>由 Resilience4j {@code resilience4j.retry.instances.*.retry-exception-predicate} 反射实例化,须有公开无参构造。
 * 仅挂在<b>幂等读</b>(searchAds / findByIds / getInterests)上;计费 / 兴趣写等非幂等操作不挂 {@code @Retry}
 * (见各 gRPC gateway)。与熔断正交:Retry 为最外层切面,重试耗尽后才落 CircuitBreaker 的降级 fallback。
 */
public class GrpcRetryablePredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable t) {
        if (t instanceof StatusRuntimeException e) {
            Status.Code code = e.getStatus().getCode();
            return code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED;
        }
        return false;
    }
}
