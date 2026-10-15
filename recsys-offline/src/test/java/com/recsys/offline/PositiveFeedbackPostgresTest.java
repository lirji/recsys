package com.recsys.offline;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import com.recsys.offline.persistence.mapper.primary.PositiveFeedbackMapper;
import com.recsys.offline.persistence.model.BehaviorSource;
import com.recsys.offline.persistence.model.PositiveFeedbackFilter;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/** 共用正反馈路径的真实PG保护：重复行为、严格时间上切、来源隔离和失败时资源关闭。 */
@Testcontainers
class PositiveFeedbackPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc,admin;
    static PositiveFeedbackMapper mapper;
    record Feedback(long userId,long itemId,String action,double value,double epoch) { }

    @BeforeAll static void seed() throws Exception {
        admin=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        String url=PG.getJdbcUrl()+(PG.getJdbcUrl().contains("?")?"&":"?")+"ApplicationName=offline-feedback-protection";
        jdbc=new JdbcTemplate(new DriverManagerDataSource(url,PG.getUsername(),PG.getPassword()));
        mapper=new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(),"classpath*:mapper/offline/primary/**/*.xml")).getMapper(PositiveFeedbackMapper.class);
        for(String table:List.of("user_behavior","behavior_log")) {
            admin.execute("CREATE TABLE "+table+"(user_id bigint,item_id bigint,action text,value double precision,ts timestamptz)");
            admin.execute("COMMENT ON TABLE "+table+" IS '独占测试正反馈源，保留重复行与可空值边界'");
            admin.execute("COMMENT ON COLUMN "+table+".user_id IS '测试用户身份'");
            admin.execute("COMMENT ON COLUMN "+table+".item_id IS '测试物品身份'");
            admin.execute("COMMENT ON COLUMN "+table+".action IS '原行为类型'");
            admin.execute("COMMENT ON COLUMN "+table+".value IS '原行为值，NULL验证SQL过滤与读取兼容'");
            admin.execute("COMMENT ON COLUMN "+table+".ts IS '行为发生时间，包含秒边界后的微秒'");
        }
        admin.execute("""
                INSERT INTO user_behavior VALUES
                (1,10,'CLICK',1,to_timestamp(100)),(1,10,'CLICK',1,to_timestamp(100)),
                (1,11,'LIKE',NULL,to_timestamp(100)),(1,12,'PLAY',1,to_timestamp(99)),
                (1,13,'RATING',4,to_timestamp(100)),(1,14,'RATING',3.999,to_timestamp(100)),
                (1,15,'RATING',NULL,to_timestamp(100)),(1,16,'IMPRESSION',1,to_timestamp(100)),
                (1,17,'CLICK',1,to_timestamp(100.000001)),
                (9223372036854775807,9223372036854775807,'LIKE',1,to_timestamp(101));
                INSERT INTO behavior_log VALUES (7,70,'CLICK',1,to_timestamp(50));
                """);
    }

    @Test void duplicateRowsRatingThresholdAndInclusiveEpochBoundaryRemainExact() {
        assertThat(pairs("user_behavior",100L)).containsExactlyInAnyOrder("1:10","1:10","1:11","1:12","1:13");
        assertEquals(7,pairs("user_behavior",null).size());
        assertThat(weighted("user_behavior",100L)).contains(new Feedback(1,11,"LIKE",0,100),new Feedback(1,13,"RATING",4,100));
        assertEquals(100,maxEpoch("user_behavior",100L));assertEquals(101,maxEpoch("user_behavior",null));
        assertNull(maxEpoch("user_behavior",97L));
        assertThat(pairs("behavior_log",100L)).containsExactly("7:70");
        assertThat(weighted("user_behavior",null)).contains(new Feedback(Long.MAX_VALUE,Long.MAX_VALUE,"LIKE",1,101));
    }

    @Test void parserKeepsDefaultsAndRejectsUntrustedSourcesBeforeQueries() {
        var defaults=new DefaultApplicationArguments();
        assertEquals("user_behavior",BehaviorQuery.table(defaults));assertNull(BehaviorQuery.maxTs(defaults));
        var replica=new DefaultApplicationArguments("--behavior-table=behavior_log","--max-ts=100");
        assertEquals("behavior_log",BehaviorQuery.table(replica));assertEquals(100L,BehaviorQuery.maxTs(replica));
        assertThrows(IllegalArgumentException.class,()->BehaviorQuery.table(new DefaultApplicationArguments("--behavior-table=user_behavior;DROP TABLE behavior_log")));
    }

    @Test void failedRowConsumerClosesConnectionAndNextScanStillWorks() {
        // MyBatis会包装回调异常；精确检查原始原因，连接释放和后续读取断言保持。
        var cause = new IllegalStateException("test consumer failure");
        var failure = assertThrows(MyBatisSystemException.class,
                () -> mapper.scanPairs(filter("user_behavior", 100L), context -> { throw cause; }));
        assertSame(cause, failure.getMostSpecificCause());
        assertEquals(0,admin.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE application_name='offline-feedback-protection'",Integer.class));
        assertEquals(5,pairs("user_behavior",100L).size());
    }

    @Test void nullableIdentitiesKeepOriginalJdbcGetLongZeroSemantics() {
        admin.execute("INSERT INTO user_behavior VALUES(NULL,NULL,'CLICK',1,to_timestamp(88)),(NULL,10,'LIKE',1,to_timestamp(88)),(1,NULL,'PLAY',1,to_timestamp(88))");
        try {
            var oldPairs = jdbc.query("SELECT user_id,item_id FROM user_behavior WHERE (action IN('CLICK','LIKE','PLAY') OR (action='RATING' AND value>=?)) AND extract(epoch FROM ts)<=?",
                    (rs, index) -> rs.getLong("user_id") + ":" + rs.getLong("item_id"), 4.0, 88L);
            assertThat(oldPairs).containsExactlyInAnyOrder("0:0", "0:10", "1:0");
            assertThat(pairs("user_behavior", 88L)).containsExactlyInAnyOrderElementsOf(oldPairs);
            assertThat(weighted("user_behavior", 88L)).containsExactlyInAnyOrder(
                    new Feedback(0, 0, "CLICK", 1, 88), new Feedback(0, 10, "LIKE", 1, 88),
                    new Feedback(1, 0, "PLAY", 1, 88));
        } finally {
            admin.execute("DELETE FROM user_behavior WHERE ts=to_timestamp(88)");
        }
    }

    private List<String> pairs(String table,Long cutoff) {
        List<String> result=new ArrayList<>();
        mapper.scanPairs(filter(table,cutoff), context->{
            var row=context.getResultObject();result.add(row.userId()+":"+row.itemId());
        });
        return result;
    }
    private List<Feedback> weighted(String table,Long cutoff) {
        List<Feedback> result=new ArrayList<>();
        mapper.scanWeighted(filter(table,cutoff), context->{
            var row=context.getResultObject();
            result.add(new Feedback(row.userId(),row.itemId(),row.action(),row.value()==null?0:row.value(),row.epoch()==null?0:row.epoch()));
        });
        return result;
    }
    private PositiveFeedbackFilter filter(String table,Long cutoff) {
        return new PositiveFeedbackFilter(BehaviorSource.fromCode(table),4,cutoff);
    }
    private Double maxEpoch(String table,Long cutoff) {
        return mapper.maxEpoch(filter(table,cutoff));
    }
}
