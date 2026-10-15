package com.recsys.recengine.exposure;

import com.recsys.common.exposure.NaturalQualification;
import org.junit.jupiter.api.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.junit.jupiter.api.Assertions.*;

/** engine 签发配置与接收端同一政策结构；默认关闭不访问 PG 或索取签名秘密。 */
@Tag("natural-exposure")
class NaturalDeliveryConfigurationTest {
    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties(NaturalDeliveryConfiguration.Properties.class)
    static class Binding {}
    @Test void disabledConfigurationHasNoNaturalDeliveryOwner() {
        new ApplicationContextRunner().withUserConfiguration(NaturalDeliveryConfiguration.class).run(ctx->{
            assertNull(ctx.getStartupFailure());assertFalse(ctx.containsBean("naturalDeliveryService"));assertFalse(ctx.containsBean("naturalDeliveryRepository"));
        });
    }
    @Test void propertiesBindWithoutLossAndIncompletePolicyFails() {
        var runner=new ApplicationContextRunner().withUserConfiguration(Binding.class).withPropertyValues("recsys.natural.policy.version=test-only",
                "recsys.natural.policy.min-ratio-bps=6000","recsys.natural.policy.min-duration-ms=1200","recsys.natural.policy.ttl-ms=120000");
        runner.run(ctx->{assertNull(ctx.getStartupFailure());assertEquals(new NaturalQualification.Policy("test-only",6000,1200,120000),ctx.getBean(NaturalDeliveryConfiguration.Properties.class).policy());});
        runner.withPropertyValues("recsys.natural.policy.min-ratio-bps=0").run(ctx->assertNotNull(ctx.getStartupFailure()));
    }
}
