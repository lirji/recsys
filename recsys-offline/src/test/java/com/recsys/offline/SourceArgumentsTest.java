package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
import com.recsys.content.persistence.model.CatalogTable;
import com.recsys.offline.persistence.model.AdEventSource;
import com.recsys.offline.persistence.model.BehaviorSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import java.util.List;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

/** 共享CLI在数据库访问前确定来源和安全失败码，原始输入不能进入公开提示。 */
class SourceArgumentsTest {
    @Test void originalDefaultsAndReplicaChoicesRemainClosedEnums() {
        var empty = new DefaultApplicationArguments();
        assertEquals(BehaviorSource.USER_BEHAVIOR, BehaviorQuery.source(empty));
        assertEquals(CatalogTable.OWNED, ItemQuery.source(empty));
        assertEquals(AdEventSource.AD_EVENT, AdEventQuery.source(empty));
        assertEquals(BehaviorSource.BEHAVIOR_LOG, BehaviorQuery.source(args("--behavior-table=behavior_log")));
        assertEquals(CatalogTable.REPLICA, ItemQuery.source(args("--item-table=item_local")));
        assertEquals(AdEventSource.AD_EVENT_LOG, AdEventQuery.source(args("--ad-event-table=ad_event_log")));
    }
    @Test void unknownSourcesHaveSpecificCodesWithoutReflectingInput() {
        checkUnknown("behavior-table", BehaviorQuery::source, ErrorCode.JOB_BEHAVIOR_SOURCE_INVALID);
        checkUnknown("item-table", ItemQuery::source, ErrorCode.JOB_ITEM_SOURCE_INVALID);
        checkUnknown("ad-event-table", AdEventQuery::source, ErrorCode.JOB_AD_EVENT_SOURCE_INVALID);
    }
    @Test void bareBlankAndRepeatedOptionsNeverSilentlyChooseDefaultSource() {
        checkShape("behavior-table", BehaviorQuery::source);
        checkShape("item-table", ItemQuery::source);
        checkShape("ad-event-table", AdEventQuery::source);
    }
    @Test void cutoffKeepsNullZeroNegativeAndExactLongButRejectsMalformedValues() {
        assertNull(BehaviorQuery.maxTs(args()));
        for (long value : new long[]{0, -1, 100, Long.MAX_VALUE}) {
            assertEquals(value, BehaviorQuery.maxTs(args("--max-ts=" + value)));
        }
        for (String value : List.of("NaN", "1.5", "9223372036854775808", "private")) {
            assertEquals(ErrorCode.JOB_INVALID_ARGUMENT, assertThrows(PublicErrorException.class,
                    () -> BehaviorQuery.maxTs(args("--max-ts=" + value))).errorCode());
        }
        checkShape("max-ts", BehaviorQuery::maxTs);
    }
    private static void checkUnknown(String key, Function<ApplicationArguments, ?> parse, ErrorCode code) {
        var failure = assertThrows(PublicErrorException.class, () -> parse.apply(args("--" + key + "=private;SELECT 1")));
        assertEquals(code, failure.errorCode());
        assertFalse(failure.getMessage().contains("private"));assertFalse(failure.getMessage().contains("SELECT"));
    }
    private static void checkShape(String key, Function<ApplicationArguments, ?> parse) {
        for (String[] values : new String[][]{{"--" + key}, {"--" + key + "= "}, {"--" + key + "=one", "--" + key + "=two"}}) {
            assertEquals(ErrorCode.JOB_INVALID_ARGUMENT, assertThrows(PublicErrorException.class,
                    () -> parse.apply(args(values))).errorCode());
        }
    }
    private static DefaultApplicationArguments args(String... values) { return new DefaultApplicationArguments(values); }
}
