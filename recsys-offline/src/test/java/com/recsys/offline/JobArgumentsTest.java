package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import static org.junit.jupiter.api.Assertions.*;

/** CLI显式错误不能悄悄采用另一个默认文件或关闭完整性校验。 */
class JobArgumentsTest {
    @Test void absentUsesDefaultsAndExistingValidInputRemainsCompatible() {
        var empty = new DefaultApplicationArguments();
        assertEquals("fallback", JobArguments.text(empty, "file", "fallback"));
        assertEquals(64, JobArguments.integer(empty, "dim", 64));
        assertTrue(JobArguments.bool(empty, "require-all", true));
        var values = new DefaultApplicationArguments("--file= input.csv ", "--dim=3", "--require-all=FaLsE");
        assertEquals("input.csv", JobArguments.text(values, "file", "fallback"));
        assertEquals(3, JobArguments.integer(values, "dim", 64));
        assertFalse(JobArguments.bool(values, "require-all", true));
    }
    @Test void explicitBareBlankAndRepeatedOptionsAreRejectedWithoutRawInput() {
        for (String[] values : new String[][]{{"--file"}, {"--file= "}, {"--file=first", "--file=private"}}) {
            var failure = assertThrows(PublicErrorException.class,
                    () -> JobArguments.text(new DefaultApplicationArguments(values), "file", "fallback"));
            assertEquals(ErrorCode.JOB_INVALID_ARGUMENT, failure.errorCode());
            assertFalse(failure.getMessage().contains("private"));
        }
        assertEquals(ErrorCode.JOB_INVALID_ARGUMENT, assertThrows(PublicErrorException.class,
                () -> JobArguments.integer(new DefaultApplicationArguments("--dim=2147483648"), "dim", 64)).errorCode());
    }
}
