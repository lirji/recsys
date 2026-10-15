package com.recsys.common.job;

import com.recsys.common.error.ErrorCode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

/** 兼容旧Redis格式，只允许状态、有效时间与目录码穿过作业状态公开边界。 */
public record JobStatusRecord(JobState state, String updatedAt, ErrorCode errorCode) {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final int TIMESTAMP_LENGTH = 19;

    /** 生产者记录当前结果；错误详情只能来自受控作业目录。 */
    public static JobStatusRecord now(JobState state, ErrorCode code) {
        return new JobStatusRecord(state, LocalDateTime.now().format(TIME), safeCode(code, state));
    }

    /** 消费旧记录时丢弃自由文本；时间无法确认时不得伪造更新时间。 */
    public static JobStatusRecord parse(String raw) {
        if (raw == null || raw.isBlank()) return unknown();
        int at = raw.indexOf('@');
        JobState state = JobState.fromCode(at < 0 ? raw : raw.substring(0, at));
        if (state == JobState.UNKNOWN) return unknown();
        if (at < 0) return new JobStatusRecord(state, null, ErrorCode.JOB_STATUS_INVALID);
        String rest = raw.substring(at + 1);
        if (rest.length() < TIMESTAMP_LENGTH || (rest.length() > TIMESTAMP_LENGTH && rest.charAt(TIMESTAMP_LENGTH) != ':')) return unknown();
        String time = rest.substring(0, TIMESTAMP_LENGTH);
        try { LocalDateTime.parse(time, TIME); }
        catch (RuntimeException malformedTime) { return unknown(); }
        ErrorCode code = rest.length() > TIMESTAMP_LENGTH + 1
                ? ErrorCode.fromCode(rest.substring(TIMESTAMP_LENGTH + 1)) : null;
        return new JobStatusRecord(state, time, safeCode(code, state));
    }

    /** raw兼容字段也只重建安全值，不返回输入原文。 */
    public String encoded() {
        return state.code() + (updatedAt == null ? "" : "@" + updatedAt)
                + (errorCode == null ? "" : ":" + errorCode.code());
    }

    private static JobStatusRecord unknown() {
        return new JobStatusRecord(JobState.UNKNOWN, null, ErrorCode.JOB_STATUS_INVALID);
    }

    private static ErrorCode safeCode(ErrorCode code, JobState state) {
        if (state == JobState.OK || state == JobState.SUCCESS || state == JobState.RUNNING) return null;
        if (code != null && code.code().startsWith("JOB_")) return code;
        if (state.failed()) return ErrorCode.JOB_EXECUTION_FAILED;
        if (state == JobState.SKIPPED) return ErrorCode.JOB_DEPENDENCY_FAILED;
        return ErrorCode.JOB_STATUS_INVALID;
    }
}
