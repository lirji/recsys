package com.recsys.common.report;

/** 离线巡检与后台消费共享的CSV状态契约，数值正常不代表依赖检查完整。 */
public enum DataQualityCheck {
    EMBEDDING("check_embedding_status", "向量覆盖率"),
    CALIBRATION("check_calibration_status", "校准偏差"),
    CATEGORY("check_category_status", "类目分布");

    private final String metricKey;
    private final String label;
    DataQualityCheck(String metricKey, String label) { this.metricKey = metricKey; this.label = label; }
    /** 稳定CSV键，避免消费者依赖枚举ordinal或展示名称。 */
    public String metricKey() { return metricKey; }
    /** 固定检查名称，可安全用于诊断日志。 */
    public String label() { return label; }

    /** 显式终态；NO_DATA和FAILED均不能参与“全部通过”判断。 */
    public enum Status {
        PASSED("PASSED"), BREACHED("BREACHED"), NO_DATA("NO_DATA"), FAILED("FAILED");
        private final String code;
        Status(String code) { this.code = code; }
        /** 对外稳定编码，不使用ordinal。 */
        public String code() { return code; }
        /** 老报表缺字段或未来未知编码返回null，由边界显示完整性未知。 */
        public static Status fromCode(String code) {
            for (Status value : values()) if (value.code.equals(code)) return value;
            return null;
        }
    }
}
