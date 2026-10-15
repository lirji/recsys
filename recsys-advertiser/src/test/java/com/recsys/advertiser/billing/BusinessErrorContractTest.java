package com.recsys.advertiser.billing;

import com.recsys.common.ad.billing.ConversionSettlement;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThat;

/** 保护本域HTTP兼容契约，内部异常不得进入公开提示。 */
@Tag("money-chain")
class BusinessErrorContractTest {
    @Test void preservesDomainClassificationsAndRecovery() {
        assertError(new ConversionSettlement.Conflict(), 409);
        assertError(new java.time.DateTimeException("private date"), 400);
        assertError(new IllegalArgumentException("private SQL token"), 400);
        assertError(new HttpMessageNotReadableException("private body", new MockHttpInputMessage(new byte[0])), 400);
        assertError(new IllegalStateException("private SQL token"), 503);
        for (int status : new int[]{400, 401, 403, 404, 409, 429, 503, 599}) {
            assertError(new ResponseStatusException(HttpStatusCode.valueOf(status), "private SQL token"), status);
        }
    }

    private void assertError(Exception failure, int status) {
        var request = new MockHttpServletRequest("PUT", "/operation/00000000-0000-0000-0000-000000000001");
        var response = new SandboxConversionController(null, null).failure(failure, request);
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("CONVERSION_" + status);
        assertThat(body.message()).isEqualTo(status == 503 ? "转化结果未知，请保留原业务ID查询或重试" : "转化请求未通过校验或当前不可访问");
        assertThat(body.path()).isEqualTo(request.getRequestURI());
        assertThat(body.fields()).isNull();
        assertThat(body.timestamp()).isPositive();
    }
}
