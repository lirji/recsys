package com.recsys.offline.adlog;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
import java.util.UUID;

/** 日志核对的持久协议值；不把ID上界解释为提交水位。 */
public final class AdEventSyncProtocol {
    public static final int PAGE_SIZE = 1000;
    public static final int MAX_PAGES = 100;
    public static final int MAX_SECONDS = 60;
    public static final int MAX_ATTEMPTS = 3;
    public enum State {
        READY("READY"), RUNNING("RUNNING"), COMPLETE("COMPLETE"), PAUSED("PAUSED");
        private final String code;
        State(String code) { this.code = code; }
        /** 持久值不依赖Java名称或顺序。 */
        public String code() { return code; }
    }
    public enum Action {
        REBUILD("REBUILD"), RESUME("RESUME");
        private final String code;
        Action(String code) { this.code = code; }
        public String code() { return code; }
    }
    public enum Reason {
        DEPENDENCY("DEPENDENCY", ErrorCode.JOB_EXECUTION_FAILED), SOURCE_CHANGED("SOURCE_CHANGED", ErrorCode.CONFLICT),
        CONTENT_CONFLICT("CONTENT_CONFLICT", ErrorCode.CONFLICT), EXHAUSTED("EXHAUSTED", ErrorCode.JOB_EXECUTION_FAILED);
        private final String code;
        private final ErrorCode error;
        Reason(String code, ErrorCode error) { this.code = code; this.error = error; }
        public String code() { return code; }
        public ErrorCode error() { return error; }
    }
    /** 内部诊断携带冲突ID，不包含源日志原文；公开提示复用ErrorCode。 */
    public static final class PageFailure extends RuntimeException {
        private final Reason reason;
        private final Long eventId;
        public PageFailure(Reason reason, Long eventId) { super(reason.error().message()); this.reason = reason; this.eventId = eventId; }
        public Reason reason() { return reason; }
        public Long eventId() { return eventId; }
    }
    public record Command(UUID id, Action action, String actor, String reason) {
        /** 必须有可追溯操作者及原因，同一命令不能混用动作。 */
        public Command {
            if (id == null || action == null || actor == null || actor.isBlank() || actor.length() > 200
                    || reason == null || reason.isBlank() || reason.length() > 1000
                    || actor.indexOf('\0') >= 0 || reason.indexOf('\0') >= 0) throw new PublicErrorException(ErrorCode.JOB_INVALID_ARGUMENT);
        }
    }
    private AdEventSyncProtocol() { }
}
