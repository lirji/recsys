package com.recsys.proto;

import com.recsys.common.ad.AdChannel;
import com.recsys.common.ad.SponsoredAd;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 钱链路 in-process ↔ gRPC 一致性闸门(P1)。
 *
 * <p>广告计费数学(GSP 次价 / bill-factor / oCPC)在 recsys-ad 纯函数里,in-process 与 gRPC 两种模式<b>共用同一套</b>——
 * 计费<b>计算</b>天然一致。真正的一致性风险在<b>序列化边界</b>:gRPC 路把 {@link SponsoredAd} 经
 * {@link AdProtoMapper} 转 proto 过网络再转回,若映射漏字段/截断精度,gRPC 就会与 in-process 收不一样的钱。
 *
 * <p>本测试锁死"计费产出经 proto round-trip 逐字段无损",尤其 {@code chargedPrice/ecpm/pctrCalibrated/bidType}——
 * 是 P3 把 {@code AD_SERVING_MODE} 翻成 grpc 默认的<b>硬前提</b>(进 CI money-chain-gate)。
 */
@Tag("money-chain")
class AdBillingProtoParityTest {

    private static SponsoredAd sample(String bidType) {
        return new SponsoredAd(
                101L, 202L, 303L, 404L, "买一送一 618 大促",
                AdChannel.SEMANTIC_AD,
                1.75,      // bid
                0.92,      // quality
                0.81,      // relevance
                0.1234,    // pctr(原始)
                0.0987,    // pctrCalibrated —— 进 eCPM 与计费
                0.157321,  // ecpm
                0.6301,    // chargedPrice —— GSP 次价,实际扣费
                2,         // position
                55L,       // creativeId
                bidType);
    }

    @Test
    void sponsoredAd_billingFields_survivesProtoRoundTrip_exactly() {
        for (String bidType : List.of("CPC", "OCPC", "CPM", "OCPM", "CPA")) {
            SponsoredAd original = sample(bidType);
            SponsoredAd back = AdProtoMapper.fromProto(AdProtoMapper.toProto(original));

            // record 相等 = 全 16 字段逐一相等(含渠道枚举与所有 double,IEEE754 精确)
            assertEquals(original, back, "SponsoredAd proto round-trip 应逐字段无损, bidType=" + bidType);

            // 计费关键字段显式断言,失败时定位清晰
            assertEquals(original.chargedPrice(), back.chargedPrice(), 0.0, "chargedPrice 必须精确保留");
            assertEquals(original.ecpm(), back.ecpm(), 0.0, "ecpm 必须精确保留");
            assertEquals(original.pctrCalibrated(), back.pctrCalibrated(), 0.0, "pctrCalibrated 必须精确保留");
            assertEquals(original.bid(), back.bid(), 0.0, "bid 必须精确保留");
            assertEquals(original.quality(), back.quality(), 0.0, "quality 必须精确保留");
            assertEquals(original.bidType(), back.bidType(), "bidType 决定扣费单位/扣费事件,必须保留");
        }
    }

    @Test
    void adsList_survivesProtoRoundTrip() {
        List<SponsoredAd> ads = List.of(sample("CPC"), sample("OCPC"));
        List<SponsoredAd> back = AdProtoMapper.fromProto(ads.stream().map(AdProtoMapper::toProto).toList());
        assertEquals(ads, back, "AdsReply 携带的 repeated 广告应整体无损");
    }
}
