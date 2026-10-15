package com.recsys.user;

import com.recsys.user.persistence.config.UserPersistenceConfiguration;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 在隔离 PostgreSQL 上锁定偏好读写契约，原生 JDBC 断言避免与被测持久层共享映射错误。 */
@Testcontainers
class UserProfilePostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private UserProfileService service;
    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void prepare() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS app_user (
                    user_id BIGINT PRIMARY KEY,
                    profile JSONB,
                    updated_at TIMESTAMP DEFAULT now()
                );
                COMMENT ON TABLE app_user IS '用户偏好契约测试独占表';
                COMMENT ON COLUMN app_user.user_id IS '应用用户标识';
                COMMENT ON COLUMN app_user.profile IS '结构化用户画像';
                COMMENT ON COLUMN app_user.updated_at IS '画像最后更新时间';
                TRUNCATE app_user;
                """);
        context = new AnnotationConfigApplicationContext();
        context.registerBean("dataSource", javax.sql.DataSource.class, () -> dataSource);
        context.register(UserPersistenceConfiguration.class, UserProfileService.class);
        context.refresh();
        service = context.getBean(UserProfileService.class);
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void mergePreservesOtherProfileKeysAndCategoryOrder() {
        jdbc.update("INSERT INTO app_user VALUES (?, ?::jsonb, now())", 1L,
                "{\"categories\":[\"old\"],\"segment\":\"retained\",\"nested\":{\"score\":3}}");
        List<String> categories = List.of("科幻", "O'Reilly", "Action", "Action", "x'); DROP TABLE app_user; --");

        service.updateInterests(1, categories);

        assertThat(service.getInterests(1)).containsExactlyElementsOf(categories);
        assertThat(jdbc.queryForObject("SELECT profile->>'segment' FROM app_user WHERE user_id=1", String.class))
                .isEqualTo("retained");
        assertThat(jdbc.queryForObject("SELECT profile->'nested'->>'score' FROM app_user WHERE user_id=1", String.class))
                .isEqualTo("3");
    }

    @Test
    void missingUserReturnsEmptyAndFirstUpdateCreatesOneRow() {
        assertThat(service.getInterests(2)).isEmpty();
        service.updateInterests(2, List.of("Drama"));
        service.updateInterests(2, List.of("Comedy"));

        assertThat(service.getInterests(2)).containsExactly("Comedy");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE user_id=2", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT updated_at IS NOT NULL FROM app_user WHERE user_id=2", Boolean.class)).isTrue();
    }

    @Test
    void nullCategoriesClearOnlyTheCategoryKey() {
        jdbc.update("INSERT INTO app_user(user_id,profile) VALUES (3, '{\"categories\":[\"old\"],\"retained\":true}')");

        service.updateInterests(3, null);

        assertThat(service.getInterests(3)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT profile->'categories' = '[]'::jsonb FROM app_user WHERE user_id=3", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT profile->>'retained' FROM app_user WHERE user_id=3", String.class)).isEqualTo("true");
    }

    @Test
    void nullProfileCanBeUpdated() {
        jdbc.update("INSERT INTO app_user(user_id,profile) VALUES (4,NULL)");
        assertThat(service.getInterests(4)).isEmpty();

        service.updateInterests(4, List.of("Action"));

        assertThat(service.getInterests(4)).containsExactly("Action");
    }

    @Test
    void malformedCategoryShapeDoesNotPretendToBeEmptyInterests() {
        jdbc.update("INSERT INTO app_user(user_id,profile) VALUES (5,'{\"categories\":{\"bad\":true}}')");
        assertThatThrownBy(() -> service.getInterests(5)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void joinsTheOwnersTransactionAndRollsBackOnFailure() {
        TransactionTemplate transaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.updateInterests(6, List.of("Drama"));
            assertThat(service.getInterests(6)).containsExactly("Drama");
            throw new IllegalStateException("模拟业务步骤失败");
        })).isInstanceOf(IllegalStateException.class).hasMessage("模拟业务步骤失败");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE user_id=6", Long.class)).isZero();
    }

    @Test
    void repeatedReadsDoNotReuseAStaleMybatisSessionResult() {
        service.updateInterests(7, List.of("old"));
        TransactionTemplate transaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));

        transaction.executeWithoutResult(status -> {
            assertThat(service.getInterests(7)).containsExactly("old");
            // 同连接的其他持久化路径写入不会主动清 MyBatis 缓存，重复读仍必须反映数据库状态。
            jdbc.update("UPDATE app_user SET profile='{\"categories\":[\"new\"]}' WHERE user_id=7");
            assertThat(service.getInterests(7)).containsExactly("new");
        });
    }

    @Test
    void bootAutoConfigurationUsesTheHostDataSourceAndLoadsXml() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, MybatisPlusAutoConfiguration.class))
                .withUserConfiguration(UserPersistenceConfiguration.class, UserProfileService.class)
                .withPropertyValues("spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword())
                .run(boot -> {
                    assertThat(boot).hasNotFailed().hasSingleBean(SqlSessionFactory.class);
                    assertThat(boot.getBean(SqlSessionFactory.class).getConfiguration().getEnvironment().getDataSource())
                            .isSameAs(boot.getBean(javax.sql.DataSource.class));
                    boot.getBean(UserProfileService.class).updateInterests(8, List.of("boot"));
                    assertThat(jdbc.queryForObject("SELECT profile->'categories'->>0 FROM app_user WHERE user_id=8", String.class))
                            .isEqualTo("boot");
                });
    }
}
