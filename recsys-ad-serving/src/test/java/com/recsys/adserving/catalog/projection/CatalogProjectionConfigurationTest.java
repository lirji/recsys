package com.recsys.adserving.catalog.projection;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 默认关闭的环境无需新库/Redis/Kafka；错误组合在连接前拒绝。 */
class CatalogProjectionConfigurationTest {
    @Test void disabledContextHasNoConnectionsWorkersOrMigrationDependency() {
        new ApplicationContextRunner().withUserConfiguration(CatalogProjectionConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(CatalogProjectionConfiguration.Settings.class)
                    .doesNotHaveBean(CatalogProjectionStore.class).doesNotHaveBean(CatalogKafkaProjection.class)
                    .doesNotHaveBean(CatalogRedisProjection.class).doesNotHaveBean(CatalogProjectionOperations.class);
            assertThat(context.getBean(CatalogProjectionConfiguration.Settings.class).enabled()).isFalse();
        });
    }

    @Test void childFlagsLegacyConsumerAndWrongReadSourceCannotBypassAdmission() {
        var configuration = new CatalogProjectionConfiguration();
        for (String flag : new String[]{"consume-enabled", "redis-enabled", "recovery-enabled"})
            assertThrows(CatalogProjectionProtocol.Failure.class, () -> configuration.catalogProjectionSettings(
                    new MockEnvironment().withProperty("recsys.ad.catalog.projection." + flag, "true")));
        assertThrows(CatalogProjectionProtocol.Failure.class, () -> configuration.catalogProjectionSettings(
                new MockEnvironment().withProperty("recsys.ad.catalog.projection.enabled", "true").withProperty("recsys.ad.catalog.consume", "true")));
        assertThrows(CatalogProjectionProtocol.Failure.class, () -> configuration.catalogProjectionSettings(
                new MockEnvironment().withProperty("recsys.ad.catalog.projection.enabled", "true").withProperty("recsys.ad.catalog.projection.consume-enabled", "true")));
    }
}
