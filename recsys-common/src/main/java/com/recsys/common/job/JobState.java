package com.recsys.common.job;

/** 作业状态使用显式稳定编码；兼容旧记录的别名，未知值绝不当成功。 */
public enum JobState {
    OK("ok"), FAIL("fail"), SKIPPED("skipped"), RUNNING("running"),
    SUCCESS("success"), FAILED("failed"), ERROR("error"), UNKNOWN("UNKNOWN");
    private final String code;
    JobState(String code) { this.code = code; }
    /** Redis与HTTP均使用code，不依赖ordinal。 */
    public String code() { return code; }
    /** 历史失败别名均属于失败，避免fail在前端被显示成普通状态。 */
    public boolean failed() { return this == FAIL || this == FAILED || this == ERROR; }
    /** 仅接受已发布的明确编码。 */
    public static JobState fromCode(String raw) {
        for (JobState state : values()) if (state.code.equals(raw)) return state;
        return UNKNOWN;
    }
}
