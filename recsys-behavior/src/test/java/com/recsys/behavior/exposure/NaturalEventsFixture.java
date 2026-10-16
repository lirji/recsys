package com.recsys.behavior.exposure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.recsys.behavior.persistence.mapper.primary.NaturalOutboxMapper;
import com.recsys.behavior.persistence.mapper.primary.NaturalReceiptMapper;
import com.recsys.common.exposure.*;
import com.recsys.common.exposure.NaturalReceipt.*;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 只创建独占 PG schema，通过真实回执服务产生事实；不读写宿主数据库。 */
class NaturalEventsFixture {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final String KEY = Base64.getEncoder().encodeToString("s15-natural-isolated-test-key-32bytes".getBytes(StandardCharsets.US_ASCII));
    static final NaturalQualificationToken TOKENS = new NaturalQualificationToken("test", Map.of("test", KEY));
    final DriverManagerDataSource ds;
    final JdbcTemplate jdbc;
    final SqlSessionTemplate sessions;
    final NaturalReceiptRepository receipts;
    final NaturalReceiptMapper receiptMapper;
    final NaturalOutboxRepository outbox;
    final NaturalEventCodec codec = new NaturalEventCodec(JSON);

    NaturalEventsFixture(PostgreSQLContainer<?> pg, boolean delivery) throws Exception {
        this(newSchema(pg));
        migrate(NaturalReceiptRepository.MIGRATION, NaturalReceiptRepository.migrationSha256(), null);
        if (delivery) upgrade();
    }

    /** 子进程只接已有独占 schema，不重复迁移或建库。 */
    NaturalEventsFixture(DriverManagerDataSource ds) throws Exception {
        this.ds = ds; jdbc = new JdbcTemplate(ds);
        sessions = new SqlSessionTemplate(SpringSqlSessions.create(ds, "classpath*:mapper/behavior/primary/**/*.xml"));
        receiptMapper = sessions.getMapper(NaturalReceiptMapper.class);
        receipts = new NaturalReceiptRepository(receiptMapper, ds, JSON);
        outbox = new NaturalOutboxRepository(sessions, new DataSourceTransactionManager(ds));
    }

    private static DriverManagerDataSource newSchema(PostgreSQLContainer<?> pg) {
        String schema = "natural_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
        admin.execute("CREATE SCHEMA " + schema);
        String url = pg.getJdbcUrl() + (pg.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema;
        return new DriverManagerDataSource(url, pg.getUsername(), pg.getPassword());
    }

    void upgrade() throws Exception { migrate(NaturalOutboxRepository.MIGRATION, NaturalOutboxRepository.migrationSha256(), null); }

    /** 失败注入只改测试读到的脚本字符串，仓库历史迁移字节不变。 */
    void migrate(String resource, String hash, String override) throws Exception {
        try (var in = new ClassPathResource(resource).getInputStream(); var c = ds.getConnection(); var s = c.createStatement()) {
            s.execute("SET recsys.migration_sha256 = '" + hash + "'");
            s.execute(override == null ? new String(in.readAllBytes(), StandardCharsets.UTF_8) : override);
        }
    }

    NaturalReceiptService service(NaturalReceiptRepository repository) {
        return new NaturalReceiptService(repository, TOKENS, NaturalQualification.TEXT_POLICY_V1,
                new DataSourceTransactionManager(ds), true);
    }

    record Accepted(NaturalQualification qualification, Command command, Result result) { }
    Accepted accept() { return accept(receipts); }

    Accepted accept(NaturalReceiptRepository repository) {
        Instant issued = receipts.databaseNow().minusSeconds(2).truncatedTo(ChronoUnit.MILLIS);
        var q = new NaturalQualification(UUID.randomUUID(), UUID.randomUUID(), "isolated-test", Long.MAX_VALUE, Long.MAX_VALUE - 1,
                2, NaturalQualification.Kind.SEARCH, NaturalQualification.Traffic.SANDBOX, "search", "rank:test", "a".repeat(64),
                NaturalQualification.TEXT_POLICY_V1, issued, issued.plusMillis(300000));
        var command = new Command(UUID.randomUUID(), "isolated-test", TOKENS.sign(q), 1000, 5000, true);
        var result = service(repository).accept(command);
        assertEquals(Status.ACCEPTED, result.status());
        return new Accepted(q, command, result);
    }

    String state(UUID id) { return jdbc.queryForObject("SELECT status FROM natural_impression_outbox WHERE event_id=?", String.class, id); }
    String payload(UUID id) { return jdbc.queryForObject("SELECT payload_json FROM natural_impression_outbox WHERE event_id=?", String.class, id); }
    int count(String table) {
        if (!Set.of("natural_impression_fact", "natural_impression_outbox", "natural_impression_outbox_replay", "test_inbox", "test_effect").contains(table)) throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    void awaitDue(UUID id) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(35).toNanos();
        while (!jdbc.queryForObject("SELECT available_at<=clock_timestamp() FROM natural_impression_outbox WHERE event_id=?", Boolean.class, id)) {
            assertTrue(System.nanoTime() < deadline, "数据库租约/退避未按期到达");
            Thread.sleep(50);
        }
    }

    NaturalOutboxMapper.Pending dead(UUID id) {
        var event = outbox.claim().orElseThrow(); assertEquals(id, event.eventId());
        assertTrue(outbox.failed(event, 1000, NaturalOutboxRepository.Error.INVALID_EVENT));
        return event;
    }
}
