package com.recsys.platform.web;

import com.recsys.common.error.ErrorCode;

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

    /** 从共享目录取稳定代码与安全提示，不将内部异常消息作为用户文案。 */
    public static ApiError of(ErrorCode error, String path, Map<String, String> fields) {
        return of(error.code(), error.message(), path, fields);
    }

    /** 兼容既有业务码；调用方只允许传入已审查的固定安全提示。 */
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
