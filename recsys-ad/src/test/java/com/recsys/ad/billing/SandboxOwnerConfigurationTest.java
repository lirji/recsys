package com.recsys.ad.billing;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

/** grpc engine 即使开启 sandbox API，也不应本地启动 ad owner 数据库与回收器。 */
@Tag("money-chain")
class SandboxOwnerConfigurationTest {
    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(BudgetConfiguration.class, CpmQuoteConfiguration.class,
                ReservationConfiguration.class, PreparationConfiguration.class);
    }
    @Test void disabledNeedsNoDatabaseOrSecret() {
        context().run(c -> assertThat(c).hasNotFailed().doesNotHaveBean(SandboxPreparationService.class));
    }
    @Test void grpcEngineHasNoOwnerBeansEvenWhenSandboxEnabled() {
        context().withPropertyValues("spring.application.name=recsys-rec-engine", "recsys.ad.serving.mode=grpc", "recsys.ad.billing.sandbox-enabled=true")
                .run(c -> assertThat(c).hasNotFailed().doesNotHaveBean(BudgetAccountService.class).doesNotHaveBean(ReservationExpiryJob.class)
                        .doesNotHaveBean(SandboxPreparationService.class));
    }
    @Test void ownerModesFailClosedWithoutRequiredDatabaseOrKeys() {
        context().withPropertyValues("spring.application.name=recsys-ad-serving", "recsys.ad.serving.mode=grpc", "recsys.ad.billing.sandbox-enabled=true")
                .run(c -> assertThat(c).hasFailed());
        context().withPropertyValues("recsys.ad.serving.mode=in-process", "recsys.ad.billing.sandbox-enabled=true")
                .run(c -> assertThat(c).hasFailed());
    }
}
