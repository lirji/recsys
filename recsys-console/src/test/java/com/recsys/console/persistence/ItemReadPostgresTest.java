package com.recsys.console.persistence;

import com.recsys.console.item.ItemController;
import com.recsys.content.MybatisContentService;
import com.recsys.content.persistence.config.ContentPersistenceConfiguration;
import com.recsys.content.persistence.mapper.ItemMapper;
import com.recsys.platform.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实PG/MyBatis到HTTP的元数据查询：故障不是查无数据，恢复后正常读取。 */
@Testcontainers
class ItemReadPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static AnnotationConfigApplicationContext context;
    MockMvc mvc;

    @BeforeAll static void schema() {
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE item(item_id bigint PRIMARY KEY,title text,category text,tags text[],description text,popularity float8)");
        jdbc.execute("COMMENT ON TABLE item IS '物品HTTP故障独占测试表'");
        for (String column : List.of("item_id", "title", "category", "tags", "description", "popularity")) {
            jdbc.execute("COMMENT ON COLUMN item." + column + " IS '物品独占测试字段'");
        }
        context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("dataSource", dataSource);
        context.register(ContentPersistenceConfiguration.class); context.refresh();
    }
    @AfterAll static void close() { if (context != null) context.close(); }
    @BeforeEach void prepare() {
        jdbc.execute("TRUNCATE item");
        // 每例独立缓存，测试故障时只查询未缓存的id；不声称故障使已缓存数据立即失效。
        var service = new MybatisContentService(context.getBean(ItemMapper.class));
        mvc = MockMvcBuilders.standaloneSetup(new ItemController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void successfulReadsKeepNullsMissingSubsetAndRequestOrder() throws Exception {
        jdbc.execute("INSERT INTO item(item_id,title,category,tags) VALUES(1,'one','Drama',ARRAY['a,b']),(2,NULL,NULL,NULL)");
        mvc.perform(get("/api/console/items").param("ids", "2,1,2,missing,999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].itemId").value(2)).andExpect(jsonPath("$[0].tags").isEmpty())
                .andExpect(jsonPath("$[1].title").value("one")).andExpect(jsonPath("$[1].tags[0]").value("a,b"));
        mvc.perform(get("/api/console/items/999")).andExpect(status().isOk()).andExpect(content().string(""));
        mvc.perform(get("/api/console/items").param("ids", "999,invalid"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test void emptyAndOversizedInputsKeepExistingBoundedParsing() throws Exception {
        jdbc.execute("INSERT INTO item(item_id,title) SELECT n,n::text FROM generate_series(1,201) n");
        mvc.perform(get("/api/console/items")).andExpect(status().isOk()).andExpect(content().json("[]"));
        String ids = IntStream.rangeClosed(1, 201).mapToObj(String::valueOf).collect(Collectors.joining(","));
        mvc.perform(get("/api/console/items").param("ids", ids)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(200)).andExpect(jsonPath("$[199].itemId").value(200));
    }

    @Test void unavailableTableReturnsSafe503ForBothRoutesThenRecovers() throws Exception {
        jdbc.execute("INSERT INTO item(item_id,title) VALUES(7,'kept')");
        jdbc.execute("ALTER TABLE item RENAME TO unavailable_item");
        try {
            for (String route : List.of("/api/console/items/7", "/api/console/items?ids=7,999")) {
                String body = mvc.perform(get(route)).andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("CONSOLE_ITEM_QUERY_FAILED"))
                        .andExpect(jsonPath("$.message").value("物品信息暂时无法读取，请稍后重试"))
                        .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                assertThat(body).doesNotContain("SELECT", "unavailable_item", "jdbc:", "Exception");
            }
            mvc.perform(get("/api/console/items").param("ids", "invalid"))
                    .andExpect(status().isOk()).andExpect(content().json("[]"));
        } finally { jdbc.execute("ALTER TABLE unavailable_item RENAME TO item"); }
        mvc.perform(get("/api/console/items/7")).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("kept"));
        mvc.perform(get("/api/console/items").param("ids", "7,999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }
}
