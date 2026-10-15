package com.recsys.common.job;

import com.recsys.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** 状态契约兼容旧记录，同时阻断自由异常文本和损坏时间进入展示。 */
class JobStatusRecordTest {
    @ParameterizedTest @ValueSource(strings = {"ok", "success", "running"})
    void successAndRunningKeepStableCodeButDropFreeText(String state) {
        var record = JobStatusRecord.parse(state + "@2026-10-16 05:00:00:private SQL token");
        assertEquals(state, record.state().code()); assertNull(record.errorCode());
        assertEquals("2026-10-16 05:00:00", record.updatedAt());
        assertFalse(record.encoded().contains("private"));
    }
    @ParameterizedTest @ValueSource(strings = {"fail", "failed", "error"})
    void oldFailuresUseSafeDefaultAndNewCodesRoundTrip(String state) {
        var legacy = JobStatusRecord.parse(state + "@2026-10-16 05:00:00:private SQL token");
        assertEquals(state, legacy.state().code());
        assertEquals(ErrorCode.JOB_EXECUTION_FAILED, legacy.errorCode());
        assertFalse(legacy.encoded().contains("private"));
        var current = JobStatusRecord.now(JobState.FAIL, ErrorCode.JOB_STATUS_WRITE_FAILED);
        assertEquals(current, JobStatusRecord.parse(current.encoded()));
    }
    @ParameterizedTest @ValueSource(strings = {"", "private SQL token", "private@2026-10-16 05:00:00",
            "ok@2026-02-30 05:00:00", "ok@2026-10-16 05:00", "ok@2026-10-16 05:00:00private"})
    void malformedRecordsRemainUnknown(String raw) {
        var record = JobStatusRecord.parse(raw);
        assertEquals(JobState.UNKNOWN, record.state()); assertNull(record.updatedAt());
        assertEquals(ErrorCode.JOB_STATUS_INVALID, record.errorCode());
        assertFalse(record.encoded().contains("private"));
    }
    @Test void missingRecordUnknownAndMissingTimestampNotInvented() {
        assertEquals(JobState.UNKNOWN, JobStatusRecord.parse(null).state());
        var running = JobStatusRecord.parse("running");
        assertEquals(JobState.RUNNING, running.state()); assertNull(running.updatedAt());
        assertEquals(ErrorCode.JOB_STATUS_INVALID, running.errorCode());
    }
    @Test void skippedAndUnrelatedCatalogCodeHaveContextualFallback() {
        var skipped = JobStatusRecord.parse("skipped@2026-10-16 05:00:00:BAD_REQUEST");
        assertEquals(ErrorCode.JOB_DEPENDENCY_FAILED, skipped.errorCode());
        assertFalse(skipped.encoded().contains("BAD_REQUEST"));
    }
}
