package com.recsys.common.recall;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 召回 explain 收集器(可变 sink)。仅当调用方需要 explain 时挂到 {@link RecallContext#explain()},
 * 让 {@code MultiChannelRecallService} 把**去重前每路原始召回数**填进来 —— 这是内部本已算出、
 * 平时随 perChannel 丢弃的真值。为 null 时召回热路径零改动、零额外分配。
 *
 * <p>非线程安全:约定由单个召回请求在合并阶段(单线程)写入,各路并行调用只读 ctx 不写 sink。
 */
public final class RecallExplain {

    private final Map<RecallChannel, Integer> perChannel = new LinkedHashMap<>();

    /** 记某召回路去重前的原始召回条数(同路多次累加)。 */
    public void record(RecallChannel channel, int rawCount) {
        perChannel.merge(channel, rawCount, Integer::sum);
    }

    /** 去重前每路原始召回数(channel → rawCount)。 */
    public Map<RecallChannel, Integer> perChannel() {
        return perChannel;
    }
}
