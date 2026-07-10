package com.recsys.platform.web;

import java.time.Instant;
import java.util.Map;

/**
 * 统一错误响应体(P0)。全站 Controller 出错时返回同一结构,替代各服务裸抛 500 / Spring 默认 whitelabel。
 *
 * <p>{@code traceId} 取自 MDC(可观测性 P2 打通后即为分布式 trace id);{@code fields} 仅在参数校验失败时填充。
 */
public record ApiError(
        String traceId,
        String code,
        String message,
        String path,
        Map<String, String> fields,
        long timestamp) {

    public static ApiError of(String code, String message, String path, Map<String, String> fields) {
        return new ApiError(currentTraceId(), code, message, path, fields, Instant.now().toEpochMilli());
    }

    private static String currentTraceId() {
        try {
            return org.slf4j.MDC.get("traceId");
        } catch (Throwable ignored) {
            return null;
        }
    }
}
