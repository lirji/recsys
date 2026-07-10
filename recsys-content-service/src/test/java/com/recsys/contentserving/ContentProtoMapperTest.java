package com.recsys.contentserving;

import com.recsys.content.Item;
import com.recsys.proto.ContentProtoMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ContentProtoMapper} 往返契约测试(P2):Item ⇄ proto 边界无损。锁死内容服务在网络边界上的契约同源。
 */
class ContentProtoMapperTest {

    @Test
    void item_roundTrips() {
        Item it = new Item(42L, "The Matrix", "Sci-Fi",
                List.of("action", "cyberpunk"), "A hacker discovers reality is a simulation.", 0.87);

        Item back = ContentProtoMapper.fromProto(ContentProtoMapper.toProto(it));

        assertEquals(it, back);   // record 值相等 ⇒ 全字段无损(含 tags 列表)
    }

    @Test
    void emptyTags_and_nulls_survive() {
        // protobuf3 无 null:空 tags / null 字段 → 空列表/空串,还原语义等价
        Item it = new Item(7L, null, null, List.of(), null, 0.0);

        Item back = ContentProtoMapper.fromProto(ContentProtoMapper.toProto(it));

        assertEquals(7L, back.itemId());
        assertEquals("", back.title());
        assertTrue(back.tags().isEmpty());
    }

    @Test
    void toItemMap_keysById() {
        var p1 = ContentProtoMapper.toProto(new Item(1L, "A", "c", List.of(), "", 1.0));
        var p2 = ContentProtoMapper.toProto(new Item(2L, "B", "c", List.of(), "", 2.0));

        Map<Long, Item> m = ContentProtoMapper.toItemMap(List.of(p1, p2));

        assertEquals(2, m.size());
        assertEquals("A", m.get(1L).title());
        assertEquals("B", m.get(2L).title());
    }
}
