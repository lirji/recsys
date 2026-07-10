package com.recsys.recengine.ad;

import com.recsys.common.ad.SearchAdsResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 降级契约(钱链路代表):ad-serving gRPC 熔断/超时时的 fallback 语义——
 * 搜索广告降级为"无广告 feed"(自然结果照常),点击/转化计费降级"记日志不阻断"。
 * content/user 网关的 fallback 同构(空 hydrate / 冷启动),不逐一重复。
 */
class GrpcAdServingGatewayFallbackTest {

    private final GrpcAdServingGateway gateway = new GrpcAdServingGateway();

    @Test
    void searchAdsFallback_returnsNoAds() {
        SearchAdsResponse resp = gateway.searchAdsFallback(
                42L, null, 3, "home", "bucketA", 0.5, new RuntimeException("ad-serving down"));
        assertNotNull(resp);
        assertTrue(resp.ads().isEmpty(), "降级应返回空广告(no-ad feed)");
        assertTrue(resp.userId() == 42L, "userId 透传");
    }

    @Test
    void billingFallbacks_doNotThrow() {
        assertDoesNotThrow(() ->
                gateway.recordClickFallback("req-1", 100L, 42L, new RuntimeException("down")));
        assertDoesNotThrow(() ->
                gateway.recordConversionFallback("req-1", 100L, 42L, new RuntimeException("down")));
    }
}
