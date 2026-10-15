package com.recsys.common.error;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 码族契约与目录保持一致，未登记状态也不能吞掉原协议分类。 */
class ErrorFamilyTest {
    @Test void registeredFamiliesPreserveEveryExistingCodeAndRecoveryMessage() {
        for (ErrorFamily family : ErrorFamily.values()) {
            for (int status : new int[]{400, 401, 403, 404, 409, 503}) {
                var registered = ErrorCode.fromCode(family.code(status));
                assertThat(registered).isNotNull();
                assertThat(registered.httpStatus()).isEqualTo(status);
                assertThat(family.message(status)).isEqualTo(registered.message());
            }
            assertThat(family.message(503)).contains("结果未知", "查询", "重试");
        }
    }

    @Test void unregisteredErrorStatusesKeepFamilyAndUseReviewedFallback() {
        assertThat(ErrorFamily.DELIVERY.code(429)).isEqualTo("DELIVERY_429");
        assertThat(ErrorFamily.CONVERSION.code(599)).isEqualTo("CONVERSION_599");
        assertThat(ErrorFamily.DELIVERY.message(429)).isEqualTo("交付请求未通过校验或当前不可访问");
        assertThat(ErrorFamily.CONVERSION.message(599)).isEqualTo("转化请求未通过校验或当前不可访问");
    }

    @Test void nonErrorStatusesCannotProducePublicFailureCodes() {
        for (int status : new int[]{0, 200, 302, 399, 600}) {
            assertThatThrownBy(() -> ErrorFamily.DELIVERY.code(status)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ErrorFamily.DELIVERY.message(status)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
