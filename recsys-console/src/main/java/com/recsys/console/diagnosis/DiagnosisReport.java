package com.recsys.console.diagnosis;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 一键诊断报告:把服务健康 + 数据质量 + 离线评估 + 链路延迟等既有信号,复用聚合成一张体检清单。
 *
 * @param overall   总判定:PASS / WARN / FAIL(取所有检查项最差档)
 * @param checks    逐项检查
 * @param checkedAt 诊断时刻(epoch ms)
 */
public record DiagnosisReport(State overall, List<Check> checks, long checkedAt) {

    /**
     * 单项检查。
     *
     * @param key    稳定键(如 service:recsys-rec-engine / data-quality / eval / latency)
     * @param name   展示名
     * @param status PASS / WARN / FAIL / INFO
     * @param detail 明细(命中的越阈值文案 / 健康说明 / 降级原因)
     */
    public record Check(String key, String name, State status, String detail) {
    }
    /** 封闭诊断状态采用稳定协议code；整体判定和告警映射共享同一语义。 */
    public enum State {
        PASS("PASS"), WARN("WARN"), FAIL("FAIL"), INFO("INFO");
        private final String code;
        State(String code) { this.code = code; }

        /** JSON保持既有字符串，不依赖ordinal或重命名后的枚举名称。 */
        @JsonValue public String code() { return code; }
    }
}
