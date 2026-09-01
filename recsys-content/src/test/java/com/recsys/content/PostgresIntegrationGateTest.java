package com.recsys.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.DockerClientFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** CI 必须真的运行 PostgreSQL 集成测试；Docker 探测异常不能静默变成全绿 skip。 */
class PostgresIntegrationGateTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "CI", matches = "(?i)true")
    void ciProvidesDockerForPostgresIntegrationTests() {
        assertThat(DockerClientFactory.instance().isDockerAvailable())
                .as("CI=true 时必须提供可用 Docker，避免 PostgreSQL 集成测试全部跳过")
                .isTrue();
    }
}
