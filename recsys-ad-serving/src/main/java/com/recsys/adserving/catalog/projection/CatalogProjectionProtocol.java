package com.recsys.adserving.catalog.projection;

import java.time.Duration;
import com.recsys.common.error.ErrorCode;

/** 目录消费的封闭状态与保护预算；错误信息只暴露固定类别，避免泄露目录原文。 */
public final class CatalogProjectionProtocol {
    public static final int MAX_ATTEMPTS = 3;
    public static final int BATCH_SIZE = 10;
    public static final int MAX_WIRE_BYTES = 1024 * 1024;
    public static final Duration LEASE = Duration.ofSeconds(30);
    public static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);
    public static final String GROUP = "recsys-ad-catalog-v1";
    public enum State {
        READY("READY"), RUNNING("RUNNING"), DONE("DONE"), PAUSED("PAUSED"),
        APPLIED("APPLIED"), STALE("STALE"), QUARANTINED("QUARANTINED");
        private final String code;
        State(String code) { this.code = code; }
        /** 持久值与 Java 名称独立；未知状态拒绝恢复，不能猜测为成功。 */
        public String code() { return code; }
        public static State fromCode(String code) {
            for (State value : values()) if (value.code.equals(code)) return value;
            throw new Failure(Code.NOT_ELIGIBLE);
        }
    }
    public enum Kind {
        INITIALIZE("INITIALIZE"), RESUME_CONSUMER("RESUME_CONSUMER"), RETRY_TASK("RETRY_TASK"),
        REFRESH_AD("REFRESH_AD"), RECHECK_RECORD("RECHECK_RECORD");
        private final String code;
        Kind(String code) { this.code = code; }
        /** 审计动作使用稳定协议值。 */
        public String code() { return code; }
    }
    /** 内部诊断原因不是第二套公开错误码；安全提示与传输分类复用统一 ErrorCode。 */
    public enum Code {
        INVALID_COMMAND("INVALID_COMMAND", ErrorCode.BAD_REQUEST), INVALID_EVENT("INVALID_EVENT", ErrorCode.UNPROCESSABLE_ENTITY),
        SOURCE_CONFLICT("SOURCE_CONFLICT", ErrorCode.CONFLICT), PAYLOAD_CONFLICT("PAYLOAD_CONFLICT", ErrorCode.CONFLICT),
        OFFSET_GAP("OFFSET_GAP", ErrorCode.CONFLICT), ACTIVE_LEASE("ACTIVE_LEASE", ErrorCode.CONFLICT),
        STALE_FENCE("STALE_FENCE", ErrorCode.CONFLICT), TARGET_NOT_EMPTY("TARGET_NOT_EMPTY", ErrorCode.CONFLICT),
        DEPENDENCY_FAILURE("DEPENDENCY_FAILURE", ErrorCode.SERVICE_UNAVAILABLE), ATTEMPTS_EXHAUSTED("ATTEMPTS_EXHAUSTED", ErrorCode.CONFLICT),
        NOT_FOUND("NOT_FOUND", ErrorCode.NOT_FOUND), NOT_ELIGIBLE("NOT_ELIGIBLE", ErrorCode.CONFLICT),
        SCHEMA_MISSING("SCHEMA_MISSING", ErrorCode.SERVICE_UNAVAILABLE), LEGACY_WRITER_FORBIDDEN("LEGACY_WRITER_FORBIDDEN", ErrorCode.CONFLICT);
        private final String code;
        private final ErrorCode errorCode;
        Code(String code, ErrorCode errorCode) { this.code = code; this.errorCode = errorCode; }
        /** 只允许用于持久诊断，不能当作公开错误目录。 */
        public String code() { return code; }
        public ErrorCode errorCode() { return errorCode; }
    }
    /** 内部失败不包含目录原文；公开提示沿用统一错误目录。 */
    public static final class Failure extends RuntimeException {
        private final Code code;
        public Failure(Code code) { super(code.errorCode().message()); this.code = code; }
        public Code code() { return code; }
        public ErrorCode errorCode() { return code.errorCode(); }
    }
    private CatalogProjectionProtocol() { }
    /** 审计命令只能来自受控内部调用，不能接受匿名恢复。 */
    public static void audit(java.util.UUID command, String actor, String reason) {
        if (command == null || actor == null || actor.isBlank() || actor.length() > 200
                || reason == null || reason.isBlank() || reason.length() > 1000) throw new Failure(Code.INVALID_COMMAND);
    }
}
