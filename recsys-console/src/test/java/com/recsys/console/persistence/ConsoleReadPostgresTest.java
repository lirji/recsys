package com.recsys.console.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.console.user.UserProfileService;
import com.recsys.console.report.ReportService;
import com.recsys.console.persistence.config.ConsolePersistenceConfiguration;
import com.recsys.console.persistence.mapper.primary.UserProfileMapper;
import com.recsys.console.persistence.mapper.primary.ReportMapper;
import com.recsys.content.persistence.config.ContentPersistenceConfiguration;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** 真实PG保护控制台只读聚合和CSV回退，避免Mapper迁移改变NULL、时间与局部失败行为。 */
@Testcontainers(disabledWithoutDocker = true)
class ConsoleReadPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("console_test").withUsername("test").withPassword("test");
    static JdbcTemplate jdbc;
    static AnnotationConfigApplicationContext context;
    @TempDir Path evalDir;

    @BeforeAll static void schema() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        jdbc.execute("CREATE TABLE app_user(user_id bigint PRIMARY KEY, profile jsonb, updated_at timestamp)");
        jdbc.execute("CREATE TABLE user_embedding(user_id bigint PRIMARY KEY)");
        jdbc.execute("CREATE TABLE item(item_id bigint PRIMARY KEY, category text)");
        jdbc.execute("CREATE TABLE user_behavior(user_id bigint, item_id bigint, action text, ts timestamp, bucket text, position int, scene text, value float8)");
        jdbc.execute("CREATE TABLE eval_report(name text PRIMARY KEY, category text NOT NULL, ts text, size_bytes bigint, created_at timestamptz, columns_json jsonb NOT NULL, rows_json jsonb NOT NULL)");
        // 名称仅来自固定测试schema，所有测试表/列都有注释。
        Map<String,List<String>> columns = Map.of(
                "app_user", List.of("user_id","profile","updated_at"), "user_embedding", List.of("user_id"),
                "item", List.of("item_id","category"), "user_behavior", List.of("user_id","item_id","action","ts","bucket","position","scene","value"),
                "eval_report", List.of("name","category","ts","size_bytes","created_at","columns_json","rows_json"));
        columns.forEach((table, names) -> {
            jdbc.execute("COMMENT ON TABLE " + table + " IS '控制台迁移隔离测试表'");
            names.forEach(column -> jdbc.execute("COMMENT ON COLUMN " + table + "." + column + " IS '控制台测试字段'"));
        });
        context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("dataSource",jdbc.getDataSource());
        context.register(ConsolePersistenceConfiguration.class,ContentPersistenceConfiguration.class);
        context.refresh();
    }
    @AfterAll static void close() { if (context != null) context.close(); }
    @BeforeEach void reset() { jdbc.execute("TRUNCATE app_user,user_embedding,item,user_behavior,eval_report"); }
    private UserProfileService profile() { return new UserProfileService(context.getBean(UserProfileMapper.class), null); }
    private ReportService report() { return new ReportService(evalDir.toString(), context.getBean(ReportMapper.class), new ObjectMapper()); }

    @Test void bootAndContentShareTheSameDataSourceAndFactory() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,MybatisPlusAutoConfiguration.class))
                .withUserConfiguration(ConsolePersistenceConfiguration.class,ContentPersistenceConfiguration.class)
                .withPropertyValues("spring.datasource.url="+PG.getJdbcUrl(),"spring.datasource.username="+PG.getUsername(),
                        "spring.datasource.password="+PG.getPassword())
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(javax.sql.DataSource.class).hasSingleBean(SqlSessionTemplate.class);
                    var session=ctx.getBean(SqlSessionTemplate.class);
                    assertThat(session.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource())
                            .isSameAs(ctx.getBean(javax.sql.DataSource.class));
                    assertThat(ctx.getBean(UserProfileMapper.class).hasEmbedding(999)).isFalse();
                    assertThat(ctx.getBean(ReportMapper.class).list()).isEmpty();
                });
    }
    @Test void optionalMappersCanBeAbsentFromServiceContext() {
        try(var empty = new AnnotationConfigApplicationContext()) {
            empty.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",Map.of("recsys.console.eval-dir",evalDir.toString())));
            empty.registerBean(ObjectMapper.class,() -> new ObjectMapper());
            empty.register(UserProfileService.class,ReportService.class);
            empty.refresh();
            assertThat(empty.getBean(UserProfileService.class).get(7).exists()).isFalse();
            assertThat(empty.getBean(ReportService.class).list()).isEmpty();
        }
    }
    @Test void profileAggregatesOnlyTheRequestedUserAndPreservesMillis() throws Exception {
        Timestamp time = Timestamp.valueOf("2026-10-15 10:20:30.123456");
        jdbc.update("INSERT INTO app_user VALUES(7,'{\"category\":\"科技\"}',?)",time);
        jdbc.execute("INSERT INTO user_embedding VALUES(7)");
        jdbc.execute("INSERT INTO item VALUES(10,'tech'),(11,'art'),(12,NULL)");
        jdbc.update("INSERT INTO user_behavior VALUES(7,10,'CLICK',?,'b',2,'home',1.5),(7,11,'CLICK',?,'a',NULL,NULL,NULL),(7,12,NULL,?,'',NULL,NULL,NULL),(8,10,'LIKE',?,'foreign',1,'home',2)",time,time,time,time);
        var view=profile().get(7);
        assertThat(view.exists()).isTrue();
        assertThat(new ObjectMapper().readTree(view.profileJson()).get("category").asText()).isEqualTo("科技");
        assertThat(view.profileUpdatedAt()).isEqualTo(time.getTime());
        assertThat(view.hasEmbedding()).isTrue();
        assertThat(view.stats().totalInteractions()).isEqualTo(3);
        assertThat(view.stats().actionCounts()).containsExactly(entry("CLICK",2L),entry("unknown",1L));
        assertThat(view.stats().distinctCategories()).isEqualTo(2);
        assertThat(view.stats().bucketsSeen()).containsExactly("a","b");
        assertThat(view.recentBehavior()).hasSize(3).allSatisfy(r -> assertThat(r.ts()).isEqualTo(time.getTime()));
        assertThat(view.recentBehavior()).filteredOn(r -> r.itemId()==10).singleElement().satisfies(r -> {
            assertThat(r.position()).isEqualTo(2); assertThat(r.value()).isEqualTo(1.5); assertThat(r.scene()).isEqualTo("home");
        });
        assertThat(view.realtime().available()).isFalse();
    }
    @Test void emptyProfileRowStillExistsAndUnknownUserDoesNot() {
        jdbc.execute("INSERT INTO app_user VALUES(7,NULL,NULL)");
        var found=profile().get(7);
        assertThat(found.exists()).isTrue(); assertThat(found.profileJson()).isNull(); assertThat(found.profileUpdatedAt()).isNull();
        var missing=profile().get(8);
        assertThat(missing.exists()).isFalse(); assertThat(missing.stats().totalInteractions()).isZero();
        assertThat(missing.recentBehavior()).isEmpty(); assertThat(missing.hasEmbedding()).isFalse();
    }
    @Test void recentRowsKeepNullFieldsAndOriginalLimit() {
        jdbc.execute("INSERT INTO user_behavior(user_id,item_id,ts) SELECT 7,n,timestamp '2026-10-15' + n*interval '1 second' FROM generate_series(1,60) n");
        jdbc.execute("INSERT INTO user_behavior(user_id) VALUES(7)");
        var view=profile().get(7);
        assertThat(view.exists()).isTrue(); assertThat(view.stats().totalInteractions()).isEqualTo(61);
        assertThat(view.recentBehavior()).hasSize(50);
        var first=view.recentBehavior().getFirst();
        assertThat(first.itemId()).isZero(); assertThat(first.ts()).isZero();
        assertThat(first.position()).isNull(); assertThat(first.value()).isNull(); assertThat(first.action()).isNull();
        assertThat(view.recentBehavior().get(1).itemId()).isEqualTo(60);
    }
    @Test void individualQueryFailureDoesNotDiscardOtherSections() {
        jdbc.execute("INSERT INTO app_user VALUES(7,'{}',NULL)");
        jdbc.execute("INSERT INTO user_behavior(user_id,item_id,action) VALUES(7,10,'CLICK')");
        jdbc.execute("ALTER TABLE item RENAME TO unavailable_item");
        try {
            var view=profile().get(7);
            assertThat(view.exists()).isTrue(); assertThat(view.stats().totalInteractions()).isEqualTo(1);
            assertThat(view.stats().distinctCategories()).isZero(); assertThat(view.recentBehavior()).hasSize(1);
        } finally { jdbc.execute("ALTER TABLE unavailable_item RENAME TO item"); }
    }
    @Test void reportsPreferDbAndDecodeJsonWithNullCells() throws Exception {
        Files.writeString(evalDir.resolve("metrics-new.csv"),"local\nignored\n");
        jdbc.execute("INSERT INTO eval_report VALUES('metrics-old.csv','eval','old',12,'2026-10-14','[\"a\"]','[[\"old\"]]'),('metrics-new.csv','eval','new',NULL,'2026-10-15 00:00:00.123456+00','[\"a\",\"b\"]','[[\"中文\",null]]')");
        var reports=report();
        assertThat(reports.list()).extracting(r -> r.fileName()).containsExactly("metrics-new.csv","metrics-old.csv");
        assertThat(reports.list().getFirst().sizeBytes()).isZero();
        assertThat(reports.list().getFirst().modifiedAt()).isEqualTo(java.time.Instant.parse("2026-10-15T00:00:00.123456Z").toEpochMilli());
        var table=reports.latest("eval");
        assertThat(table.columns()).containsExactly("a","b"); assertThat(table.rows().getFirst()).containsExactly("中文",null);
        assertThat(reports.latest("missing")).isNull();
    }
    @Test void absentOrInvalidDbReportFallsBackToCsv() throws Exception {
        Files.writeString(evalDir.resolve("metrics-one.csv"),"a,b\nx,y\n");
        var reports=report();
        assertThat(reports.list()).singleElement().satisfies(r -> assertThat(r.category()).isEqualTo("eval"));
        assertThat(reports.read("metrics-one.csv").rows()).containsExactly(List.of("x","y"));
        jdbc.execute("INSERT INTO eval_report VALUES('metrics-one.csv','eval','one',1,NULL,'{}','[]')");
        assertThat(reports.read("metrics-one.csv").rows()).containsExactly(List.of("x","y"));
        assertThat(reports.list().getFirst().modifiedAt()).isZero();
    }
    @Test void missingOptionalDatabaseAndUnsafeNamesKeepPriorBehavior() throws Exception {
        var profile=new UserProfileService(null,null).get(7);
        assertThat(profile.exists()).isFalse(); assertThat(profile.stats().actionCounts()).isEmpty();
        Files.writeString(evalDir.resolve("metrics-one.csv"),"a\nx\n");
        var reports=new ReportService(evalDir.toString(),null,new ObjectMapper());
        assertThat(reports.list()).hasSize(1); assertThat(reports.read("metrics-one.csv").rows()).containsExactly(List.of("x"));
        assertThatThrownBy(() -> reports.read("../secret.csv")).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(() -> reports.read("missing.csv")).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(404));
    }
    @Test void unavailableReportTableFallsBackToCsv() throws Exception {
        Files.writeString(evalDir.resolve("metrics-one.csv"),"a\nx\n");
        jdbc.execute("ALTER TABLE eval_report RENAME TO unavailable_report");
        try {
            assertThat(report().list()).hasSize(1);
            assertThat(report().read("metrics-one.csv").rows()).containsExactly(List.of("x"));
        } finally { jdbc.execute("ALTER TABLE unavailable_report RENAME TO eval_report"); }
    }

    @Test void failedDatabaseWithoutMatchingCsvDoesNotPretendTheReportIsAbsent() throws Exception {
        jdbc.execute("ALTER TABLE eval_report RENAME TO unavailable_report");
        try {
            var reports = report();
            assertUnavailable(reports::list);
            assertUnavailable(() -> reports.latest("eval"));
            var mvc = reportHttp(reports);
            var body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/console/report/index"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("CONSOLE_REPORT_UNAVAILABLE"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("unavailable_report", "SELECT", "jdbc:");
            Files.writeString(evalDir.resolve("ad-report-one.csv"), "a\nx\n");
            assertThat(reports.list()).hasSize(1);
            assertThat(reports.latest("ad-report").rows()).containsExactly(List.of("x"));
            assertUnavailable(() -> reports.latest("eval"));
            assertUnavailable(() -> reports.read("metrics-absent.csv"));
            // 同名有效文件可恢复读取；失败上下文不能阻止已有的真实回退能力。
            Files.writeString(evalDir.resolve("metrics-one.csv"), "a\ny\n");
            assertThat(reports.latest("eval").rows()).containsExactly(List.of("y"));
        } finally { jdbc.execute("ALTER TABLE unavailable_report RENAME TO eval_report"); }
    }

    @Test void malformedDatabaseJsonWithoutValidFallbackIsUnavailableNot404() throws Exception {
        jdbc.execute("INSERT INTO eval_report VALUES('metrics-bad.csv','eval','bad',1,NULL,'{}','[]')");
        var reports = report();
        assertUnavailable(() -> reports.latest("eval"));
        assertUnavailable(() -> reports.read("metrics-bad.csv"));
        reportHttp(reports).perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/console/report/file").param("name", "metrics-bad.csv"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("CONSOLE_REPORT_UNAVAILABLE"));
        jdbc.execute("UPDATE eval_report SET columns_json='[]',rows_json='[null]'");
        assertUnavailable(() -> reports.read("metrics-bad.csv"));
        Files.writeString(evalDir.resolve("metrics-bad.csv"), "a\nrestored\n");
        assertThat(reports.read("metrics-bad.csv").rows()).containsExactly(List.of("restored"));
    }

    @Test void invalidCsvAndUnreadableCatalogReturnSafeErrors() throws Exception {
        var reports = new ReportService(evalDir.toString(), null, new ObjectMapper());
        Files.writeString(evalDir.resolve("metrics-private.csv"), "a\n\"private unclosed value\n");
        var body = reportHttp(reports).perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/console/report/file").param("name", "metrics-private.csv"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isInternalServerError())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("CONSOLE_REPORT_READ_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("private", evalDir.toString());
        Path invalidDirectory = Files.createTempFile(evalDir, "not-a-directory", ".txt");
        assertUnavailable(() -> new ReportService(invalidDirectory.toString(), null, new ObjectMapper()).list());
        Files.createSymbolicLink(evalDir.resolve("metrics-missing.csv"), evalDir.resolve("nonexistent-target"));
        assertUnavailable(reports::list); // 目录项无法stat不能伪造成0字节、0时间。
    }

    @Test void trueMissingAndInvalidFileNamesKeepTheirHttpCodes() throws Exception {
        var mvc = reportHttp(report());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/console/report/file").param("name", "../private.csv"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/console/report/file").param("name", "missing.csv"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("NOT_FOUND"));
        assertThat(report().latest("eval")).isNull();
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(com.recsys.common.error.PublicErrorException.class)
                .satisfies(failure -> assertThat(((com.recsys.common.error.PublicErrorException) failure).errorCode())
                        .isEqualTo(com.recsys.common.error.ErrorCode.CONSOLE_REPORT_UNAVAILABLE));
    }

    private static org.springframework.test.web.servlet.MockMvc reportHttp(ReportService reports) {
        return org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new com.recsys.console.report.ReportController(reports))
                .setControllerAdvice(new com.recsys.platform.web.GlobalExceptionHandler()).build();
    }
}
