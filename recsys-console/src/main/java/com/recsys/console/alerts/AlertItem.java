package com.recsys.console.alerts;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 一条告警。由既有信号(服务健康 DOWN / 数据质量越阈值 / 链路延迟越界)派生,不落库、纯只读快照。
 *
 * @param level   INFO / WARN / ERROR
 * @param source  来源(service / data-quality / latency)
 * @param message 人类可读消息
 * @param ts      观测时刻(epoch ms)
 */
public record AlertItem(Level level, Source source, String message, long ts) {
    /** 稳定展示编码与显式严重程度顺序，禁止ordinal决定排序。 */
    public enum Level {
        ERROR("ERROR", 0), WARN("WARN", 1), INFO("INFO", 2);
        private final String code;
        private final int priority;
        Level(String code, int priority) { this.code = code; this.priority = priority; }

        /** JSON沿用原INFO/WARN/ERROR协议。 */
        @JsonValue public String code() { return code; }
        /** 展示顺序独立于枚举声明顺序。 */
        public int priority() { return priority; }
    }

    /** 告警只投影已定义的三类信号，协议编码独立于Java名称。 */
    public enum Source {
        SERVICE("service"), DATA_QUALITY("data-quality"), LATENCY("latency");
        private final String code;
        Source(String code) { this.code = code; }
        /** 保持既有source字符串供前端分类。 */
        @JsonValue public String code() { return code; }
    }
}
