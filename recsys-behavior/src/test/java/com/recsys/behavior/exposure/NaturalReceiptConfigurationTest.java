package com.recsys.behavior.exposure;

import com.recsys.common.exposure.NaturalQualification;
import org.junit.jupiter.api.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.junit.jupiter.api.Assertions.*;

/** 关闭能力不要求数据库/秘密；启用前政策绑定必须完整，不能悄悄补业务默认值。 */
@Tag("natural-exposure")
class NaturalReceiptConfigurationTest {
    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties(NaturalReceiptConfiguration.Properties.class)
    static class Binding {}
    @Test void disabledConfigurationDoesNotCreateReceiptOwner() {
        new ApplicationContextRunner().withUserConfiguration(NaturalReceiptConfiguration.class).run(ctx->{
            assertNull(ctx.getStartupFailure());assertFalse(ctx.containsBean("naturalReceiptService"));assertFalse(ctx.containsBean("naturalReceiptRepository"));
        });
    }
    @Test void explicitPolicyBindsAndPartialOrInvalidPolicyFailsStartup() {
        var runner=new ApplicationContextRunner().withUserConfiguration(Binding.class).withPropertyValues("recsys.natural.policy.version=test-only",
                "recsys.natural.policy.min-ratio-bps=6000","recsys.natural.policy.min-duration-ms=1200","recsys.natural.policy.ttl-ms=120000");
        runner.run(ctx->{ assertNull(ctx.getStartupFailure());assertEquals(new NaturalQualification.Policy("test-only",6000,1200,120000),ctx.getBean(NaturalReceiptConfiguration.Properties.class).policy()); });
        runner.withPropertyValues("recsys.natural.policy.ttl-ms=1").run(ctx->assertNotNull(ctx.getStartupFailure()));
        new ApplicationContextRunner().withUserConfiguration(Binding.class).withPropertyValues("recsys.natural.policy.version=partial")
                .run(ctx->assertNotNull(ctx.getStartupFailure()));
    }
}
