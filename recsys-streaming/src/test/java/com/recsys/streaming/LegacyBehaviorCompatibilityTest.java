package com.recsys.streaming;

import com.recsys.common.behavior.LegacyBehaviorAccepted;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** 旧Flink仅保留原解析，版本化流在连接任何组件之前拒绝；不代替S19窗口恢复验证。 */
@Tag("behavior-reliability")
class LegacyBehaviorCompatibilityTest {
    @Test void reservedTopicsFailBeforeParsingOtherSettingsOrConnecting() {
        for(String root:new String[]{LegacyBehaviorAccepted.LIVE_TOPIC,LegacyBehaviorAccepted.HISTORY_TOPIC,"natural-impression-accepted-v1"}) {
            for(String topic:new String[]{root,root+".sandbox"}) {
                var error=assertThrows(IllegalArgumentException.class,()->RealtimeFeatureJob.main(new String[]{"--topic",topic,"--redis-port","invalid"}));
                assertEquals("旧行为入口不能消费版本化保留事件流",error.getMessage());
            }
        }
        assertDoesNotThrow(()->LegacyBehaviorAccepted.requireLegacyTopic("behavior-events"));
        assertDoesNotThrow(()->LegacyBehaviorAccepted.requireLegacyTopic("legacy-behavior.sandbox"));
    }
    @Test void originalFormatsRetainWeightsLongIdsAndRepeatedMessagesWithoutHashDeduplication() {
        var parser=new RealtimeFeatureJob.CategoryEnricher("unused","unused","unused");
        for(String action:new String[]{"CLICK","LIKE","PLAY","RATING"})for(String exposure:new String[]{",\"exposureId\":\"original\"",""}) {
            String json="{\"userId\":9223372036854775807,\"itemId\":9223372036854775806,\"action\":\""+action+"\",\"value\":3,\"scene\":\"old\",\"bucket\":\"A\",\"ts\":1"+exposure+"}";
            for(int repeat=0;repeat<2;repeat++) {
                var event=parser.map(json);assertNotNull(event);assertEquals(Long.MAX_VALUE,event.userId);assertEquals(Long.MAX_VALUE-1,event.itemId);
                assertTrue(event.isPositive());assertEquals(action,event.action);assertEquals(action.equals("LIKE")?2:action.equals("RATING")?3:1,event.weight());
            }
        }
        assertFalse(parser.map("{\"action\":\"IMPRESSION\"}").isPositive());
    }
}
