package com.recsys.proto.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;

import java.util.concurrent.TimeUnit;

/**
 * gRPC 客户端统一 deadline(P1)。为每个出站调用注入 {@code withDeadlineAfter},根治"跨进程 gRPC 无限挂起"——
 * 下游卡死时调用方按 deadline 失败(DEADLINE_EXCEEDED),配合 resilience4j 熔断/降级快速兜底,而非线程池耗尽级联雪崩。
 *
 * <p>尊重调用方显式设置的 deadline(若已设则不覆盖),仅对未设 deadline 的调用兜底。
 */
public class GrpcDeadlineClientInterceptor implements ClientInterceptor {

    private final long deadlineMs;

    public GrpcDeadlineClientInterceptor(long deadlineMs) {
        this.deadlineMs = deadlineMs;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        CallOptions options = callOptions.getDeadline() == null
                ? callOptions.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                : callOptions;
        return next.newCall(method, options);
    }
}
