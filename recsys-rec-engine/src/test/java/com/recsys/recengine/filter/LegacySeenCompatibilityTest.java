package com.recsys.recengine.filter;

import com.recsys.common.behavior.LegacyBehaviorAccepted;
import com.recsys.common.constant.RedisKeys;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只验证旧入口配置和解析；真实Redis确认/offset恢复必须在C15C完成。 */
@Tag("behavior-reliability")
class LegacySeenCompatibilityTest {
    @Test void reservedTopicsFailSpringConstructionEvenWhenListenerDisabled() {
        for(String root:new String[]{LegacyBehaviorAccepted.LIVE_TOPIC,LegacyBehaviorAccepted.HISTORY_TOPIC,"natural-impression-accepted-v1"}) {
            for(String topic:new String[]{root,root+".sandbox"})runner().withPropertyValues("recsys.behavior.topic="+topic,"recsys.behavior.seen-consume=false")
                    .run(context->assertNotNull(context.getStartupFailure()));
        }
        runner().run(context->{assertNull(context.getStartupFailure());assertNotNull(context.getBean(SeenItemsConsumer.class));});
        runner().withPropertyValues("recsys.behavior.topic=legacy-behavior.sandbox").run(context->assertNull(context.getStartupFailure()));
    }
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withBean(StringRedisTemplate.class,()->mock(StringRedisTemplate.class)).withUserConfiguration(SeenItemsConsumer.class);
    }
    @Test @SuppressWarnings("unchecked") void eightFieldAndOlderSevenFieldMessagesKeepEveryPositiveInvocationAndLongIds() {
        var redis=mock(StringRedisTemplate.class);SetOperations<String,String> sets=mock(SetOperations.class);when(redis.opsForSet()).thenReturn(sets);
        var consumer=new SeenItemsConsumer(redis,"behavior-events");
        for(String action:new String[]{"CLICK","LIKE","PLAY","RATING"})for(String exposure:new String[]{",\"exposureId\":\"original\"",""}) {
            String json="{\"userId\":9223372036854775807,\"itemId\":9223372036854775806,\"action\":\""+action+"\",\"value\":2,\"scene\":\"old\",\"bucket\":\"A\",\"ts\":1"+exposure+"}";
            consumer.onEvent(json);consumer.onEvent(json); // 无eventId的同内容消息仍逐次应用，不造hash身份。
        }
        verify(sets,times(16)).add(RedisKeys.seenItems(Long.MAX_VALUE),Long.toString(Long.MAX_VALUE-1));
        consumer.onEvent("{\"userId\":1,\"itemId\":2,\"action\":\"IMPRESSION\",\"value\":1,\"scene\":\"old\",\"bucket\":\"A\",\"ts\":1}");
        verifyNoMoreInteractions(sets);
    }
}
