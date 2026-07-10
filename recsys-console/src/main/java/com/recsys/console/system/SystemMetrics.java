package com.recsys.console.system;

/**
 * 系统实时指标(来自 Prometheus 的真实观测值,非写死)。
 * 数据源:rec-engine 的 Micrometer 计时器 {@code recsys.recommend.duration}(publishPercentileHistogram)
 * 经 Prometheus 抓取后,由 console BFF 用 PromQL histogram_quantile / rate 求值。
 * 观测栈未起(docker compose --profile obs)或近窗口无流量时优雅降级:available=false 或对应字段为 null。
 */
public record SystemMetrics(
        boolean available,          // Prometheus 可达(能查到就返回 true;个别指标可能因无流量为 null)
        String source,              // "prometheus" | "unavailable"
        String message,             // 人类可读说明(降级原因 / 就绪提示)
        Double recommendP99Ms,      // 推荐链路 P99 延迟(毫秒)
        Double recommendAvgMs,      // 推荐链路平均延迟(毫秒)
        Double recommendQps,        // 推荐 QPS(近 1 分钟速率)
        Double adP99Ms,             // 广告链路 P99 延迟(毫秒),可选
        long checkedAt              // 探测时刻(epoch ms)
) {
    static SystemMetrics unavailable(String message, long checkedAt) {
        return new SystemMetrics(false, "unavailable", message, null, null, null, null, checkedAt);
    }
}
