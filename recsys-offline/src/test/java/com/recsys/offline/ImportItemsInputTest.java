package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
import com.recsys.content.ContentService;
import com.recsys.content.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实临时文件验证输入策略；内容写入为替身，不触发下载或共享存储。 */
@ExtendWith(OutputCaptureExtension.class)
class ImportItemsInputTest {
    @TempDir Path directory;
    ContentService content;
    ImportItemsJob job;
    @BeforeEach void setup() throws Exception {
        content = mock(ContentService.class); job = new ImportItemsJob(content);
        ReflectionTestUtils.setField(job, "movielensPath", directory.toString());
        movies("1,\"Film, One\",Drama|Comedy\n2,Two,(no genres listed)\n");
    }
    @Test void validRatingsAndQuotedMoviesKeepOriginalFields() throws Exception {
        ratings("1,1,4,100\n2,1,3,101\n"); job.run(new DefaultApplicationArguments());
        var rows = ArgumentCaptor.forClass(Item.class); verify(content, times(2)).save(rows.capture());
        assertEquals("Film, One", rows.getAllValues().get(0).title());
        assertEquals(2.0, rows.getAllValues().get(0).popularity());
        assertEquals("Unknown", rows.getAllValues().get(1).category());
        assertEquals(0.0, rows.getAllValues().get(1).popularity());
    }
    @Test void genuinelyMissingOptionalRatingsKeepsExplicitZeroFallback(CapturedOutput output) throws Exception {
        job.run(new DefaultApplicationArguments());
        var rows = ArgumentCaptor.forClass(Item.class); verify(content, times(2)).save(rows.capture());
        assertTrue(rows.getAllValues().stream().allMatch(item -> item.popularity() == 0));
        assertTrue(output.getAll().contains("[JOB_POPULARITY_FALLBACK]"));
    }
    @Test void malformedRatingsCannotPublishHalfCounts() throws Exception {
        ratings("1,1,4,100\nprivate\n");
        var failure = assertThrows(PublicErrorException.class, () -> job.run(new DefaultApplicationArguments()));
        assertEquals(ErrorCode.JOB_INPUT_INVALID, failure.errorCode());
        assertFalse(failure.getMessage().contains("private")); verify(content, never()).save(any());
    }
    @Test void existingButUnreadableAsFileRatingsDoNotBecomeZeroPopularity() throws Exception {
        Files.createDirectory(directory.resolve("ratings.csv"));
        assertEquals(ErrorCode.JOB_INPUT_UNAVAILABLE, assertThrows(PublicErrorException.class,
                () -> job.run(new DefaultApplicationArguments())).errorCode());
        verify(content, never()).save(any());
    }
    @Test void malformedMovieRowsKeepValidWritesButReportPartialFailure() throws Exception {
        movies("1,One,Drama\nmalformed\n2,Two,Comedy\n");
        assertEquals(ErrorCode.JOB_IMPORT_PARTIAL_FAILED, assertThrows(PublicErrorException.class,
                () -> job.run(new DefaultApplicationArguments())).errorCode());
        verify(content, times(2)).save(any());
    }
    @Test void invalidMovieIdentityReportsSafeInputErrorAfterEarlierWrite() throws Exception {
        movies("1,One,Drama\nprivate,Two,Comedy\n");
        var failure = assertThrows(PublicErrorException.class, () -> job.run(new DefaultApplicationArguments()));
        assertEquals(ErrorCode.JOB_INPUT_INVALID, failure.errorCode());
        assertFalse(failure.getMessage().contains("private")); verify(content, times(1)).save(any());
    }
    @Test void downstreamFailureIsNotMisclassifiedAsBadInput() throws Exception {
        doThrow(new NumberFormatException("private storage failure")).when(content).save(any());
        var runner = new JobRunner(java.util.List.of(job));
        assertEquals(ErrorCode.JOB_EXECUTION_FAILED, assertThrows(PublicErrorException.class,
                () -> runner.run(new DefaultApplicationArguments("--job=import-items"))).errorCode());
    }

    private void movies(String rows) throws Exception { Files.writeString(directory.resolve("movies.csv"), "movieId,title,genres\n" + rows); }
    private void ratings(String rows) throws Exception { Files.writeString(directory.resolve("ratings.csv"), "userId,movieId,rating,timestamp\n" + rows); }
}
