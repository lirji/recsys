package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 分发失败必须可观察，预检失败不允许前序作业已产生副作用。 */
class JobRunnerErrorTest {
    @Test void unknownLaterNamePreventsAnyJobFromRunning() {
        var calls = new AtomicInteger();
        var runner = new JobRunner(List.of(job("first", calls::incrementAndGet)));
        var failure = assertThrows(PublicErrorException.class, () -> runner.run(args("--job=first", "--job=private")));
        assertEquals(ErrorCode.JOB_UNKNOWN, failure.errorCode()); assertEquals(0, calls.get());
        assertFalse(failure.getMessage().contains("private"));
    }
    @Test void absentJobAllowsBootButBareJobIsInvalid() throws Exception {
        var runner = new JobRunner(List.of());
        runner.run(args());
        assertEquals(ErrorCode.JOB_UNKNOWN, assertThrows(PublicErrorException.class, () -> runner.run(args("--job"))).errorCode());
    }
    @Test void arbitraryFailureStopsLaterJobsAndKeepsCauseInternal() {
        var calls = new AtomicInteger();
        var internal = new IllegalStateException("private SQL token");
        var runner = new JobRunner(List.of(job("bad", () -> { throw internal; }), job("later", calls::incrementAndGet)));
        var failure = assertThrows(PublicErrorException.class, () -> runner.run(args("--job=bad", "--job=later")));
        assertEquals(ErrorCode.JOB_EXECUTION_FAILED, failure.errorCode()); assertSame(internal, failure.getCause());
        assertFalse(failure.getMessage().contains("private")); assertEquals(0, calls.get());
    }
    @Test void explicitJobFailureKeepsItsSafeCode() {
        var safe = new PublicErrorException(ErrorCode.JOB_STATUS_WRITE_FAILED);
        var runner = new JobRunner(List.of(job("bad", () -> { throw safe; })));
        assertSame(safe, assertThrows(PublicErrorException.class, () -> runner.run(args("--job=bad"))));
    }
    @Test void checkedInterruptionKeepsCancellationFlagAndCode() {
        var runner = new JobRunner(List.of(new OfflineJob() {
            public String name() { return "interrupted"; }
            public void run(ApplicationArguments args) throws Exception { throw new InterruptedException("private token"); }
        }));
        try {
            assertEquals(ErrorCode.JOB_INTERRUPTED, assertThrows(PublicErrorException.class,
                    () -> runner.run(args("--job=interrupted"))).errorCode());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    static ApplicationArguments args(String... args) { return new DefaultApplicationArguments(args); }
    static OfflineJob job(String name, Runnable action) {
        return new OfflineJob() {
            public String name() { return name; }
            public void run(ApplicationArguments args) { action.run(); }
        };
    }
}
