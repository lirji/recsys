package com.recsys.ad.billing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

/** 回执另有默认关闭开关，不能因为开始预算/准备就自动接受收费。 */
@Tag("money-chain")
class ImpressionConfigurationTest {
    @Test void bothSandboxAndReceiptFlagsAreRequiredAndGrpcEngineHasNoLocalOwner() {
        var runner=new ApplicationContextRunner().withUserConfiguration(ImpressionReceiptConfiguration.class);
        runner.withPropertyValues("recsys.ad.billing.sandbox-enabled=true").run(c->assertThat(c).hasNotFailed().doesNotHaveBean(ImpressionReceiptService.class));
        runner.withPropertyValues("recsys.ad.billing.receipts-enabled=true").run(c->assertThat(c).hasNotFailed().doesNotHaveBean(ImpressionReceiptService.class));
        runner.withPropertyValues("recsys.ad.billing.receipts-enabled=true","recsys.ad.billing.sandbox-enabled=true","recsys.ad.serving.mode=grpc","spring.application.name=recsys-rec-engine")
                .run(c->assertThat(c).hasNotFailed().doesNotHaveBean(ImpressionReceiptService.class));
        runner.withPropertyValues("recsys.ad.billing.receipts-enabled=true","recsys.ad.billing.sandbox-enabled=true")
                .run(c->assertThat(c).hasFailed());
    }
}
