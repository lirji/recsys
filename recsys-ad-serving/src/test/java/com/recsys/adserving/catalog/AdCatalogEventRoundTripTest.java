package com.recsys.adserving.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.AdCatalogEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 广告目录事件 JSON 往返测试(P1b):publisher(advertiser)以 ObjectMapper 序列化、consumer(ad-serving)反序列化,
 * 两端都用普通 Jackson 直打 record —— 依赖 `-parameters`(父 pom 已开)按组件名绑定。此测试锁死"事件契约过网络无损"。
 */
class AdCatalogEventRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void snapshot_roundTrips() throws Exception {
        AdCatalogEvent e = new AdCatalogEvent(
                101L, true, 303L, 202L, "Buy Now!", "http://x/lp", 1.2,
                "active", "approved", "OCPC", 5.0, 777L,
                List.of(new AdCatalogEvent.Bidword(1L, "action", "EXACT", 1.5),
                        new AdCatalogEvent.Bidword(2L, "movie", "BROAD", 0.8)),
                List.of(new AdCatalogEvent.Creative(9L, "Alt title", "http://x/lp2", "approved")),
                "[0.1,0.2,0.3]",
                1_700_000_000_000L);

        AdCatalogEvent back = mapper.readValue(mapper.writeValueAsString(e), AdCatalogEvent.class);

        assertEquals(e, back);                       // record 值相等 ⇒ 全字段无损(含嵌套 bidwords/creatives)
        assertEquals(2, back.bidwords().size());
        assertEquals("action", back.bidwords().get(0).keyword());
        assertEquals(1, back.creatives().size());
        assertEquals("[0.1,0.2,0.3]", back.embedding());   // #3:事件携带的向量往返无损
        assertTrue(back.servable());
    }

    @Test
    void tombstone_roundTrips() throws Exception {
        AdCatalogEvent tomb = AdCatalogEvent.tombstone(101L, 1_700_000_000_000L);

        AdCatalogEvent back = mapper.readValue(mapper.writeValueAsString(tomb), AdCatalogEvent.class);

        assertEquals(101L, back.adId());
        assertFalse(back.servable());
        assertTrue(back.bidwords().isEmpty());       // 紧凑构造器把 null → List.of()
        assertTrue(back.creatives().isEmpty());
    }
}
