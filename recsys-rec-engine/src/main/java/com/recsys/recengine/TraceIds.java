package com.recsys.recengine;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 请求级 traceId 解析器 —— 让「前端响应里的 traceId」与「结构化日志 %X{traceId} / Grafana Tempo 里的 traceId」是同一个值,
 * 从而前端拿到 traceId 即可直接在 Tempo 按此 id 钻取跨进程调用链。
 *
 * <p>优先取 micrometer-tracing 当前 span 的 <b>OTel traceId</b>(32 位 hex,与日志/Tempo 完全一致);
 * 无活动 span(采样未命中 / 关闭追踪 / 无 Tracer bean)时回退到短 UUID —— 该回退值仅作本地标识,Tempo 查不到。
 * {@link Tracer} 用 {@link ObjectProvider} 可选注入,追踪不可用绝不影响推荐/广告主链路。
 */
@Component
public class TraceIds {

    private final ObjectProvider<Tracer> tracerProvider;

    public TraceIds(ObjectProvider<Tracer> tracerProvider) {
        this.tracerProvider = tracerProvider;
    }

    /** 当前请求的 traceId:优先 OTel span 的 traceId,回退短 UUID。 */
    public String current() {
        try {
            Tracer tracer = tracerProvider.getIfAvailable();
            if (tracer != null) {
                Span span = tracer.currentSpan();
                if (span != null) {
                    String traceId = span.context().traceId();
                    if (traceId != null && !traceId.isBlank()) {
                        return traceId;
                    }
                }
            }
        } catch (RuntimeException ignore) {
            // 追踪链路不可用不应影响主链路;回退到本地短 id
        }
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
