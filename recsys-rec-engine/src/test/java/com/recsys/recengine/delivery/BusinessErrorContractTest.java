package com.recsys.recengine.delivery;

import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.common.exposure.NaturalReceipt;
import com.recsys.platform.web.ApiError;
import com.recsys.recengine.exposure.NaturalDeliveryController;
import com.recsys.recengine.feed.FeedDeliveryController;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.server.ResponseStatusException;

import java.util.function.BiFunction;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

/** 固定重构前的公开契约；不同领域异常必须保留原状态，未知结果不能伪造成拒绝。 */
@Tag("money-chain")
class BusinessErrorContractTest {
    record Boundary(String name, String family, String rejected, String unknown,
                    BiFunction<Exception, HttpServletRequest, ResponseEntity<ApiError>> handler,
                    RuntimeException conflict, RuntimeException denied, boolean invalidProofDenied) {}

    static Stream<Boundary> boundaries() {
        return Stream.of(
            new Boundary("cpm", "DELIVERY", "交付请求未通过校验或当前不可访问",
                    "交付结果未知，请保留原 operationId 查询或重试", new SandboxDeliveryController(null)::failure,
                    new SandboxDelivery.Conflict(), new Reservation.AccessDenied(), false),
            new Boundary("cpc", "DELIVERY", "交付请求未通过校验或当前不可访问",
                    "交付结果未知，请保留原 operationId 查询或重试", new SandboxClickDeliveryController(null)::failure,
                    new SandboxDelivery.Conflict(), new Reservation.AccessDenied(), true),
            new Boundary("cpa", "DELIVERY", "交付请求未通过校验或当前不可访问",
                    "交付结果未知，请保留原 operationId 查询或重试", new SandboxConversionDeliveryController(null)::failure,
                    new SandboxDelivery.Conflict(), new Reservation.AccessDenied(), true),
            new Boundary("impression", "RECEIPT", "回执请求未通过校验或当前不可访问",
                    "账务结果未知，请按原 receiptId 查询或重试", new SandboxImpressionController(null)::failure,
                    new ImpressionReceipt.Conflict(), new Reservation.AccessDenied(), true),
            new Boundary("click", "CLICK_RECEIPT", "点击请求未通过校验或当前不可访问",
                    "点击结果未知，请按原ID查询或重试", new SandboxClickReceiptController(null)::failure,
                    new ClickSettlement.Conflict(), new Reservation.AccessDenied(), true),
            new Boundary("conversion", "CONVERSION_RECEIPT", "CPA请求未通过校验或当前不可访问",
                    "CPA结果未知，请按原ID查询或重试", new SandboxConversionImpressionController(null)::failure,
                    new ConversionSettlement.Conflict(), new Reservation.AccessDenied(), true),
            new Boundary("natural", "NATURAL_DELIVERY", "自然交付请求未通过校验或当前不可访问",
                    "自然交付结果未知，请保留原 operationId 查询或重试", new NaturalDeliveryController(null)::failure,
                    new NaturalReceipt.Conflict(), new NaturalReceipt.AccessDenied(), false),
            new Boundary("feed", "FEED_DELIVERY", "混排交付请求未通过校验或当前不可访问",
                    "混排交付结果未知，请保留原 operationId 查询或重试", new FeedDeliveryController(null)::failure,
                    new NaturalReceipt.Conflict(), new NaturalReceipt.AccessDenied(), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("boundaries")
    void preservesDomainClassificationsAndSafeRecovery(Boundary boundary) {
        assertError(boundary, boundary.conflict(), 409);
        assertError(boundary, boundary.denied(), 403);
        assertError(boundary, new SandboxDelivery.InvalidProof(), boundary.invalidProofDenied() ? 403 : 503);
        assertError(boundary, new IllegalArgumentException("private SQL token"), 400);
        assertError(boundary, new HttpMessageNotReadableException("private body", new MockHttpInputMessage(new byte[0])), 400);
        assertError(boundary, new IllegalStateException("private SQL token"), 503);
        // 未登记的429也必须保持旧码族，而不是null或通用500。
        for (int status : new int[]{400, 401, 403, 404, 409, 429, 503, 599}) {
            assertError(boundary, new ResponseStatusException(HttpStatusCode.valueOf(status), "private SQL token"), status);
        }
    }

    private void assertError(Boundary boundary, Exception failure, int status) {
        var request = new MockHttpServletRequest("PUT", "/operation/00000000-0000-0000-0000-000000000001");
        var response = boundary.handler().apply(failure, request);
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo(boundary.family() + "_" + status);
        assertThat(body.message()).isEqualTo(status == 503 ? boundary.unknown() : boundary.rejected());
        assertThat(body.path()).isEqualTo(request.getRequestURI());
        assertThat(body.fields()).isNull();
        assertThat(body.timestamp()).isPositive();
    }
}
