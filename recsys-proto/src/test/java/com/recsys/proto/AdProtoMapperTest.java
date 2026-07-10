package com.recsys.proto;

import com.recsys.common.ad.AdChannel;
import com.recsys.common.ad.SponsoredAd;
import com.recsys.common.query.CategoryScore;
import com.recsys.common.query.StructuredQuery;
import com.recsys.common.query.TermWeight;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AdProtoMapper} 边界契约(ACL)的往返无损测试:领域 record ⇄ proto wire。
 * 这是微服务化广告服务的在线/离线契约同源要求在"网络边界"上的体现——
 * mapper 若丢字段/错映射,rec-engine 与 recsys-ad-serving 就会对不上,是拆分后最隐蔽的首因。
 */
class AdProtoMapperTest {

    @Test
    void structuredQuery_roundTrips() {
        float[] emb = {0.1f, -0.2f, 0.3f};
        StructuredQuery sq = new StructuredQuery(
                "Action Movies",
                "action movies",
                List.of(new TermWeight("action", 1.0), new TermWeight("movies", 0.5)),
                List.of(new CategoryScore("Action", 0.9), new CategoryScore("Adventure", 0.4)),
                List.of("action movies", "action film"),
                emb);

        StructuredQuery back = AdProtoMapper.fromProto(AdProtoMapper.toProto(sq));

        assertEquals(sq.raw(), back.raw());
        assertEquals(sq.normalized(), back.normalized());
        assertEquals(2, back.terms().size());
        assertEquals("action", back.terms().get(0).term());
        assertEquals(1.0, back.terms().get(0).weight());
        assertEquals(2, back.intents().size());
        assertEquals("Action", back.intents().get(0).category());
        assertEquals(0.9, back.intents().get(0).score());
        assertEquals(List.of("action movies", "action film"), back.rewrites());
        assertArrayEquals(emb, back.embedding(), 1e-6f);
    }

    @Test
    void structuredQuery_nullEmbedding_mapsToEmptyThenNull() {
        // embedding 不可用(Gemini/BGE 缺失)时为 null;proto 无 null → 空 repeated → 还原回 null(等价语义)
        StructuredQuery sq = new StructuredQuery("q", "q", List.of(), List.of(), List.of(), null);

        StructuredQuery back = AdProtoMapper.fromProto(AdProtoMapper.toProto(sq));

        assertNull(back.embedding());
        assertTrue(back.terms().isEmpty());
        assertTrue(back.intents().isEmpty());
    }

    @Test
    void sponsoredAd_roundTrips_allSixteenFields() {
        SponsoredAd ad = new SponsoredAd(
                101L, 202L, 303L, 404L,
                "Buy Now!", AdChannel.KW_EXACT,
                1.5, 1.2, 0.8, 0.15, 0.12, 3.6, 0.9, 2, 505L, "OCPC");

        SponsoredAd back = AdProtoMapper.fromProto(AdProtoMapper.toProto(ad));

        assertEquals(ad, back);   // record 值相等 ⇒ 16 字段逐一无损(含 channel 枚举 + bidType)
    }

    @Test
    void sponsoredAd_nullChannel_survivesAsNull() {
        SponsoredAd ad = new SponsoredAd(
                1L, 2L, 3L, 4L, "t", null,
                1.0, 1.0, 1.0, 0.1, 0.1, 1.0, 0.5, 1, 0L, "CPC");

        SponsoredAd back = AdProtoMapper.fromProto(AdProtoMapper.toProto(ad));

        assertNull(back.channel());
        assertEquals("CPC", back.bidType());
        assertEquals(0L, back.creativeId());
    }
}
