package com.recsys.common.ad;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link BidwordInvCodec} 契约测试 —— 竞价词倒排 member 的离线写(SeedAdsJob/AdIndexSync)
 * 与在线读(JdbcAdRecallService)一致性,类比 SparseFeatureEncoder 之于排序。
 *
 * <p>锁死:member 布局 {@code adId:bidwordId:itemId:advertiserId:quality}、score=出价、
 * 字段回填到 {@link AdCandidate} 的正确位置、脏数据解析返回 null(不拖垮召回)。
 */
class BidwordInvCodecTest {

    @Test
    void encode_layoutIsStable() {
        // 布局若变,离线写入的历史倒排即无法被在线正确解析 → 此断言即护栏
        assertEquals("7:3:4567:9:1.5", BidwordInvCodec.encode(7, 3, 4567, 9, 1.5));
    }

    @Test
    void roundTrip_fieldsMapToCorrectSlots() {
        String member = BidwordInvCodec.encode(7, 3, 4567, 9, 1.5);
        double bid = 2.8; // score 独立于 member,走 ZSet score
        AdCandidate c = BidwordInvCodec.decode(member, bid, AdChannel.KW_EXACT);

        assertEquals(7, c.adId());
        assertEquals(3, c.bidwordId());
        assertEquals(4567, c.itemId());
        assertEquals(9, c.advertiserId());
        assertEquals(1.5, c.quality());
        // bid 来自 score;recallScore 也取 bid(KW 路召回分=出价)
        assertEquals(bid, c.bid());
        assertEquals(bid, c.recallScore());
        assertEquals(AdChannel.KW_EXACT, c.channel());
    }

    @Test
    void decode_malformed_returnsNull() {
        // 字段不足 5 段 → null(不抛,脏数据跳过)
        assertNull(BidwordInvCodec.decode("7:3:4567", 1.0, AdChannel.KW_EXACT));
        // 非数字 → null
        assertNull(BidwordInvCodec.decode("a:b:c:d:e", 1.0, AdChannel.KW_EXACT));
        // 空串 → null
        assertNull(BidwordInvCodec.decode("", 1.0, AdChannel.KW_EXACT));
    }

    @Test
    void decode_extraTrailingFields_tolerated() {
        // 多出的段被忽略(前 5 段有效),向后兼容未来在 member 追加字段
        AdCandidate c = BidwordInvCodec.decode("7:3:4567:9:1.5:extra", 1.0, AdChannel.KW_BROAD);
        assertEquals(7, c.adId());
        assertEquals(1.5, c.quality());
    }
}
