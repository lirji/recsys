package com.recsys.console.diagnosis;

import java.util.List;

/**
 * 一键诊断报告:把服务健康 + 数据质量 + 离线评估 + 链路延迟等既有信号,复用聚合成一张体检清单。
 *
 * @param overall   总判定:PASS / WARN / FAIL(取所有检查项最差档)
 * @param checks    逐项检查
 * @param checkedAt 诊断时刻(epoch ms)
 */
public record DiagnosisReport(String overall, List<Check> checks, long checkedAt) {

    /**
     * 单项检查。
     *
     * @param key    稳定键(如 service:recsys-rec-engine / data-quality / eval / latency)
     * @param name   展示名
     * @param status PASS / WARN / FAIL / INFO
     * @param detail 明细(命中的越阈值文案 / 健康说明 / 降级原因)
     */
    public record Check(String key, String name, String status, String detail) {
    }
}
