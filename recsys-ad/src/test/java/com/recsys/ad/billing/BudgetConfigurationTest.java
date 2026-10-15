package com.recsys.ad.billing;

import org.mybatis.spring.SqlSessionTemplate;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 开关与数据源装配测试，默认不建立新账务入口或自动运行迁移。 */
@Tag("money-chain")
class BudgetConfigurationTest {
    @Test
    void disabledByDefaultNeedsNoDatabase() {
        new ApplicationContextRunner().withUserConfiguration(BudgetConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(BudgetRepository.class).doesNotHaveBean(BudgetAccountService.class);
        });
    }

    @Test
    void wrongDataSourceAndUnboundedTimeoutRejectStartup() throws Exception {
        var configuration = new BudgetConfiguration();
        var source = new DriverManagerDataSource();
        var repository = new BudgetRepository(new SqlSessionTemplate(SpringSqlSessions.create(source, "classpath*:mapper/ad/addb/**/*.xml")));
        assertThrows(IllegalArgumentException.class, () -> configuration.budgetAccountService(repository, source, 0));
        assertThrows(IllegalArgumentException.class, () -> configuration.budgetAccountService(repository, source, 31));
        assertThrows(IllegalArgumentException.class,
                () -> configuration.budgetAccountService(repository, new DriverManagerDataSource(), 5));
    }
}
