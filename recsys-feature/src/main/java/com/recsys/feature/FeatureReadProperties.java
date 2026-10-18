package com.recsys.feature;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 只读特征预算；故障使用模型现有缺特征降级，写入不受此丢弃策略影响。 */
@ConfigurationProperties(prefix = "recsys.feature.read")
public class FeatureReadProperties {
    private int threads = 4;
    private long timeoutMs = 100;
    private int maxBatchSize = 1024;
    public int getThreads() { return threads; }
    public void setThreads(int value) { threads = value; }
    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long value) { timeoutMs = value; }
    public int getMaxBatchSize() { return maxBatchSize; }
    public void setMaxBatchSize(int value) { maxBatchSize = value; }
}
