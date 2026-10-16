package com.recsys.behavior.events;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.PostgreSQLContainer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 复用B1独占源schema，只显式追加V002；不更改既有迁移或测试数据。 */
class BehaviorHistoryFixture extends BehaviorEventsFixture {
    BehaviorHistoryFixture(PostgreSQLContainer<?> pg) throws Exception { super(pg, true); migrateHistory(null, BehaviorHistoryRepository.migrationSha256()); }
    BehaviorHistoryRepository history() { return new BehaviorHistoryRepository(sessions, new DataSourceTransactionManager(ds), new MockEnvironment()); }
    UUID sourceId() { return jdbc.queryForObject("SELECT source_id FROM behavior_event_source", UUID.class); }
    void seed(long id) { jdbc.update("INSERT INTO user_behavior(id,user_id,item_id,action,value,scene,ts) VALUES(?,7,42,'PLAY',2,'unknown-writer','2001-02-03 04:05:06.123456')", id); }
    BehaviorHistoryRepository.Report register(UUID id, Long... ids) { return history().captureHistory(id, sourceId(), List.of(ids), "trusted-operator", "explicit manifest"); }
    void migrateHistory(String override, String hash) throws Exception { migrateHistory(this, override, hash); }
    static void migrateHistory(BehaviorEventsFixture f, String override, String hash) throws Exception {
        try (var input = new ClassPathResource(BehaviorHistoryRepository.MIGRATION).getInputStream();
             var connection = f.ds.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SET recsys.migration_sha256='" + hash + "'");
            statement.execute(override == null ? new String(input.readAllBytes(), StandardCharsets.UTF_8) : override);
        }
    }
}
