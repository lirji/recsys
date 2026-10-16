package com.recsys.recengine.filter.seen;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 只按Kafka技术坐标登记；内容摘要不是旧消息的业务去重身份。 */
public record SeenRecord(UUID recordId, String stream, String clusterId, String topicId, String topic,
        String groupId, int partitionId, long recordOffset, byte[] keyBytes, byte[] valueBytes,
        int keyLength, int valueLength, String digest, boolean payloadComplete,
        String firstClassification, String firstError, String status, int attempts, long epoch,
        long fence, OffsetDateTime leaseUntil, UUID activeCommandId, String lastError,
        OffsetDateTime createdAt, OffsetDateTime lastSuccessAt, boolean leaseActive, boolean due) {
    /** 固定存储码由领域枚举集中定义，数据库仍使用name而不是ordinal。 */
    public enum State { READY, APPLYING, APPLIED, NO_EFFECT, QUARANTINED, PAUSED }
    public enum CommandState { READY, RUNNING, SUCCEEDED, FAILED }
    public enum CommandKind { INITIALIZE, RETRY, RECHECK, RESTORE }
    public enum Classification { POSITIVE, NO_EFFECT, INVALID_DATA }
    /** 对外日志禁止自动展开原文和用户标识。 */
    @Override public String toString() { return "SeenRecord[" + recordId + "," + status + "]"; }
    /** 仅这些终态满足技术确认条件，不把隔离称为投影成功。 */
    public boolean acknowledgeable() { return status.equals(SeenRecord.State.APPLIED.name()) || status.equals(SeenRecord.State.NO_EFFECT.name()) || status.equals(SeenRecord.State.QUARANTINED.name()); }
    /** 内部固定错误，不携带原始异常cause，避免框架日志泄露消息。 */
    public enum Code {
        INVALID_COMMAND, COMMAND_CONFLICT, NOT_FOUND, NOT_ELIGIBLE, ACTIVE_LEASE, STILL_INVALID,
        SOURCE_UNAVAILABLE, IDENTITY_CHANGED, OFFSET_GAP, DEPENDENCY_UNAVAILABLE, INTERNAL_ERROR,
        MIGRATION_INVALID, COORDINATE_CONFLICT, INVALID_DATA, OVERSIZE, STREAM_PAUSED, STREAM_STOPPED
    }
    public static final class Failure extends IllegalStateException {
        private final Code code;
        public Failure(Code code) { super("Seen操作未完成: " + code); this.code = code; }
        public Code code() { return code; }
    }
}
