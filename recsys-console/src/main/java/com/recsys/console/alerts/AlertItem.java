package com.recsys.console.alerts;

/**
 * 一条告警。由既有信号(服务健康 DOWN / 数据质量越阈值 / 链路延迟越界)派生,不落库、纯只读快照。
 *
 * @param level   INFO / WARN / ERROR
 * @param source  来源(service / data-quality / latency)
 * @param message 人类可读消息
 * @param ts      观测时刻(epoch ms)
 */
public record AlertItem(String level, String source, String message, long ts) {
}
