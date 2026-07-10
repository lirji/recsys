package com.recsys.recengine.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.constant.ActionType;
import com.recsys.common.dto.BehaviorEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * behavior-events 契约往返测试(P4):behavior(publisher)以 ObjectMapper 序列化 {@link BehaviorEvent}、
 * rec-engine {@link SeenItemsConsumer} 反序列化,两端普通 Jackson 直打 record + {@link ActionType} 枚举。
 * 锁死"行为事件过 Kafka 无损"——尤其 action 枚举按 name 往返(SeenItemsConsumer 靠 action().name() 判正反馈)。
 */
class BehaviorEventRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void behaviorEvent_roundTrips_withEnumAction() throws Exception {
        BehaviorEvent ev = new BehaviorEvent(
                42L, 1001L, ActionType.CLICK, 4.5, "search", "recall:plus|rank:onnx", 1_700_000_000_000L);

        BehaviorEvent back = mapper.readValue(mapper.writeValueAsString(ev), BehaviorEvent.class);

        assertEquals(ev, back);                       // record 值相等 ⇒ 全字段无损
        assertEquals(ActionType.CLICK, back.action());
        assertEquals("CLICK", back.action().name());  // SeenItemsConsumer 的正反馈判定口径
    }

    @Test
    void impression_isNotPositive_ratingIs() throws Exception {
        // 与 SeenItemsConsumer/DbSeenItemsSource 的 action IN ('CLICK','LIKE','PLAY','RATING') 口径一致
        BehaviorEvent impr = new BehaviorEvent(1L, 2L, ActionType.IMPRESSION, 0, "feed", null, 1L);
        BehaviorEvent rating = new BehaviorEvent(1L, 3L, ActionType.RATING, 5, "feed", null, 2L);

        assertEquals("IMPRESSION", mapper.readValue(mapper.writeValueAsString(impr), BehaviorEvent.class).action().name());
        assertEquals("RATING", mapper.readValue(mapper.writeValueAsString(rating), BehaviorEvent.class).action().name());
    }
}
