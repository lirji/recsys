package com.recsys.console.system;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 系统实时指标查询配置。PromQL 默认值对齐 monitoring/alert-rules.yml 与 RecommendOrchestrator 的
 * {@code recsys.recommend.duration} 计时器,可经环境变量 / yml 覆盖(换指标名或时间窗时无需改代码)。
 */
@ConfigurationProperties(prefix = "recsys.console.metrics")
public class SystemMetricsProperties {

    /** 关闭后 /metrics 直接返回 unavailable,不外呼 Prometheus。 */
    private boolean enabled = true;

    /** Prometheus 基地址(obs profile 默认 localhost:9090)。 */
    private String prometheusUrl = "http://localhost:9090";

    private int connectTimeoutMs = 400;
    private int readTimeoutMs = 800;

    /** 推荐链路 P99 延迟(秒)。 */
    private String recommendP99Query =
            "histogram_quantile(0.99, sum(rate(recsys_recommend_duration_seconds_bucket[5m])) by (le))";

    /** 推荐链路平均延迟(秒)= sum(rate(_sum)) / sum(rate(_count))。 */
    private String recommendAvgQuery =
            "sum(rate(recsys_recommend_duration_seconds_sum[5m])) / sum(rate(recsys_recommend_duration_seconds_count[5m]))";

    /** 推荐 QPS(近 1 分钟)。 */
    private String recommendQpsQuery =
            "sum(rate(recsys_recommend_duration_seconds_count[1m]))";

    /** 广告链路 P99 延迟(秒),可选。 */
    private String adP99Query =
            "histogram_quantile(0.99, sum(rate(recsys_ad_duration_seconds_bucket[5m])) by (le))";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPrometheusUrl() {
        return prometheusUrl;
    }

    public void setPrometheusUrl(String prometheusUrl) {
        this.prometheusUrl = prometheusUrl;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public String getRecommendP99Query() {
        return recommendP99Query;
    }

    public void setRecommendP99Query(String recommendP99Query) {
        this.recommendP99Query = recommendP99Query;
    }

    public String getRecommendAvgQuery() {
        return recommendAvgQuery;
    }

    public void setRecommendAvgQuery(String recommendAvgQuery) {
        this.recommendAvgQuery = recommendAvgQuery;
    }

    public String getRecommendQpsQuery() {
        return recommendQpsQuery;
    }

    public void setRecommendQpsQuery(String recommendQpsQuery) {
        this.recommendQpsQuery = recommendQpsQuery;
    }

    public String getAdP99Query() {
        return adP99Query;
    }

    public void setAdP99Query(String adP99Query) {
        this.adP99Query = adP99Query;
    }
}
