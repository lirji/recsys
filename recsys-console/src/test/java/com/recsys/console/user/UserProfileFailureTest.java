package com.recsys.console.user;

import com.recsys.console.persistence.mapper.primary.UserProfileMapper;
import com.recsys.common.error.ErrorCode;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 游标中途失败与可选依赖缺失属于未知，不能因已有部分结果而声称读取完成。 */
class UserProfileFailureTest {
    @Test void partiallyReadActionCountsAreDiscardedButOtherSectionsRemain() {
        var mapper = mock(UserProfileMapper.class);
        when(mapper.profile(7)).thenReturn(new UserProfileMapper.Profile(7, "{}", null));
        when(mapper.buckets(7)).thenReturn(List.of("kept"));
        doAnswer(call -> {
            ResultHandler<UserProfileMapper.ActionCount> handler = call.getArgument(1);
            var context = new DefaultResultContext<UserProfileMapper.ActionCount>();
            context.nextResultObject(new UserProfileMapper.ActionCount("CLICK", 3));
            handler.handleResult(context);
            throw new IllegalStateException("private SQL failure after first row");
        }).when(mapper).actionCounts(eq(7L), any());
        var view = new UserProfileService(mapper, null).get(7);
        assertEquals(Boolean.TRUE, view.exists());
        assertEquals("{}", view.profileJson());
        assertEquals(List.of("kept"), view.stats().bucketsSeen());
        assertNull(view.stats().totalInteractions());
        assertTrue(view.stats().actionCounts().isEmpty());
        assertEquals(ErrorCode.CONSOLE_USER_DATA_UNAVAILABLE.code(), view.errors().get(UserProfileView.Section.ACTIONS));
        assertFalse(view.toString().contains("private"));
    }

    @Test void missingDependenciesAreExplicitlyUnknown() {
        var view = new UserProfileService(null, null).get(7);
        assertNull(view.exists()); assertNull(view.hasEmbedding());
        assertNull(view.stats().totalInteractions()); assertNull(view.stats().distinctCategories());
        assertNull(view.realtime().recCached()); assertNull(view.realtime().seenCount());
        assertFalse(view.realtime().available());
        assertEquals(7, view.errors().size());
    }
}
