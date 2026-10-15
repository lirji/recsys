package com.recsys.recengine.feed;

import com.recsys.ad.billing.FeedCandidateConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

/** 默认关闭不能要求额外表、签名秘密或账务连接，也不能自动启用旧频控替身。 */
@Tag("feed-exposure") @Tag("natural-exposure")
class FeedDeliveryConfigurationTest {
    @Test void defaultsDoNotAssembleFeedOrFrequencyOwners() {
        new ApplicationContextRunner().withUserConfiguration(FeedDeliveryConfiguration.class,FeedCandidateConfiguration.class).run(ctx->{
            assertNull(ctx.getStartupFailure()); assertFalse(ctx.containsBean("feedDeliveryService")); assertFalse(ctx.containsBean("feedCandidateService"));
        });
    }
    @Test void explicitEnableFailsWithoutRequiredSchemaAndSigningConfiguration() {
        new ApplicationContextRunner().withUserConfiguration(FeedDeliveryConfiguration.class).withPropertyValues("recsys.feed.delivery-enabled=true")
                .run(ctx->assertNotNull(ctx.getStartupFailure()));
    }
}
