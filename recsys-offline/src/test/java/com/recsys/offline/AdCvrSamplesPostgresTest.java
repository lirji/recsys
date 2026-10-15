package com.recsys.offline;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 两个真实分片+主库验证延迟删失、原NULL/未来事件语义和固定CSV输出。 */
@Testcontainers
class AdCvrSamplesPostgresTest {
    @Container static final PostgreSQLContainer<?> ZERO=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final PostgreSQLContainer<?> ONE=new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate zero,one,events;
    @TempDir Path output;
    @BeforeAll static void prepare() {
        zero=db(ZERO,ZERO.getJdbcUrl());one=db(ONE,ONE.getJdbcUrl());
        for(var db:List.of(zero,one)) {
            create(db,"advertiser","advertiser_id BIGINT PRIMARY KEY");
            create(db,"ad","ad_id BIGINT PRIMARY KEY,advertiser_id BIGINT,item_id BIGINT");
            create(db,"bidword","id BIGINT PRIMARY KEY,ad_id BIGINT");
            create(db,"ad_creative","creative_id BIGINT PRIMARY KEY,ad_id BIGINT,status TEXT");
        }
        zero.execute("CREATE SCHEMA cvr");events=db(ZERO,url());
        for(String table:List.of("ad_event","ad_event_log"))create(events,table,"request_id TEXT,user_id BIGINT,ad_id BIGINT,event_type TEXT,ts TIMESTAMPTZ,offset_s DOUBLE PRECISION");
    }
    @BeforeEach void reset() {
        zero.execute("TRUNCATE ad");one.execute("TRUNCATE ad");events.execute("TRUNCATE ad_event,ad_event_log");
        zero.execute("INSERT INTO ad VALUES(0,2,100),(2,2,102),(4,2,NULL),(6,2,106)");
        one.execute("INSERT INTO ad VALUES(1,1,101),(3,1,103),(9223372036854775807,1,9223372036854775807)");
    }
    @Test void defaultMergesBothShardsEarliestConversionAndCensoredFuture() throws Exception {
        seed();run("freeze");var rows=rows();assertEquals(7,rows.size());
        assertEquals(List.of("1","11","2","3","5","7","8"),rows.stream().map(r->r[3]).toList());
        assertEquals(List.of("1","1","0","1","0","0","0"),rows.stream().map(r->r[0]).toList());
        assertEquals(List.of("1.0","0.5","-1.0","-1.0","-1.0","-1.0","-1.0"),rows.stream().map(r->r[1]).toList());
        assertEquals(List.of("10.0","9.5","2.0","1.0","-2.0","30.0","0.0"),rows.stream().map(r->r[2]).toList());
        assertEquals("1,1.0,10.0,1,101,A,0.6,0.6,0.3,0.8,0.9,train",String.join(",",rows.getFirst()));
        assertEquals("0,-1.0,2.0,2,102,,0.6,0.6,0.3,0.8,0.8,train",String.join(",",rows.get(2)));
        String expected=Files.readString(csv());assertEquals("8ce52d982ed82e235461988041eafb9c16bbca5b24e900813fae4c4c74101765",sha());run("freeze");assertEquals(expected,Files.readString(csv()));
    }
    @Test void replicaWindowAndSeedPreserveWholeCsv() throws Exception {
        seed();events.execute("INSERT INTO ad_event_log SELECT * FROM ad_event");run("freeze","--days=0","--seed=7");String expected=Files.readString(csv());assertEquals("b5ab8ca91dac00a4f3f09fdd6e0ca608ccd2e496c3ecccbfde77d7464ebf98ff",sha());
        assertEquals(9,rows().size());run("freeze","--days=-1","--seed=7","--ad-event-table=ad_event_log");assertEquals(expected,Files.readString(csv()));
        run("freeze","--days=1");assertEquals(List.of("3","5","8"),rows().stream().map(r->r[3]).toList());
    }
    @Test void nullIdentityTimestampAndLiteralNullRequestKeepOriginalMatching() throws Exception {
        events.execute("INSERT INTO ad_event VALUES(NULL,NULL,NULL,'CLICK',NULL,NULL),('null',9,0,'CONVERSION',NULL,-86400),('max',9223372036854775807,9223372036854775807,'CLICK',NULL,-86400)");
        run("freeze","--days=0");var rows=rows();assertEquals(2,rows.size());assertEquals("0",rows.getFirst()[3]);assertEquals("100",rows.getFirst()[4]);
        assertEquals("1",rows.getFirst()[0]);assertTrue(Double.parseDouble(rows.getFirst()[1])>10000);assertEquals("0.0",rows.getFirst()[2]);assertEquals(Long.toString(Long.MAX_VALUE),rows.get(1)[3]);assertEquals(Long.toString(Long.MAX_VALUE),rows.get(1)[4]);
    }
    @Test void emptyAndQueryFailuresPreserveOldFile() throws Exception {
        keep();run("freeze");assertEquals("keep",Files.readString(csv()));
        seed();events.execute("ALTER TABLE ad_event RENAME TO hidden_events");
        try{fail("read-only","BadSqlGrammarException");assertEquals("keep",Files.readString(csv()));}finally{events.execute("ALTER TABLE hidden_events RENAME TO ad_event");}
        fail("read-only","非法 --ad-event-table","--ad-event-table=ad_event;DROP TABLE ad");assertEquals("keep",Files.readString(csv()));
    }
    @Test void missingItemsOnlyWritesHeaderAndLateFeatureFailureKeepsPartialRows() throws Exception {
        events.execute("INSERT INTO ad_event VALUES('missing',1,4,'CLICK',NULL,-86400)");run("freeze");assertEquals(0,rows().size());
        events.execute("TRUNCATE ad_event");seed();fail("user-failure","fixture-user-failure");assertEquals(3,rows().size());assertEquals(List.of("1","11","2"),rows().stream().map(r->r[3]).toList());
    }
    @Test void autocommitReadsAlsoCloseConnectionsAndReturnSameLabels() throws Exception {
        seed();run("autocommit","--days=0");var rows=rows();assertEquals(9,rows.size());assertEquals("1",rows.getFirst()[0]);assertEquals("1.0",rows.getFirst()[1]);assertTrue(Double.parseDouble(rows.getFirst()[2])>=10.0);
    }
    private static void seed() {
        events.execute("INSERT INTO ad_event(request_id,user_id,ad_id,event_type,offset_s) VALUES"
                +"('r1',1,1,'CLICK',-864000),('r1',1,1,'CONVERSION',-691200),('r1',1,1,'CONVERSION',-777600),('r1',11,1,'CLICK',-820800),"
                +"('r2',2,2,'CLICK',-172800),('r2',2,2,'CONVERSION',86400),('other',2,2,'CONVERSION',-86400),"
                +"('r3',3,3,'CLICK',-86400),('r3',3,3,'CONVERSION',-172800),('none',4,4,'CLICK',-86400),('none',4,999,'CLICK',-86400),"
                +"('future',5,6,'CLICK',172800),('future',5,6,'CONVERSION',86400),('old',6,1,'CLICK',-2678400),"
                +"('edge',7,1,'CLICK',-2592000),('outside',70,1,'CLICK',-2592000.000001),('now',8,3,'CLICK',0),('ignored',90,1,'IMPRESSION',0)");
    }
    private static JdbcTemplate db(PostgreSQLContainer<?> pg,String url){return new JdbcTemplate(new DriverManagerDataSource(url,pg.getUsername(),pg.getPassword()));}
    private static String url(){return ZERO.getJdbcUrl()+"&currentSchema=cvr&ApplicationName=cvr-reader";}
    private static void create(JdbcTemplate db,String table,String columns){db.execute("CREATE TABLE "+table+"("+columns+")");db.execute("COMMENT ON TABLE "+table+" IS 'CVR迁移独占测试'");for(String c:columns.split(","))db.execute("COMMENT ON COLUMN "+table+"."+c.strip().split(" ")[0]+" IS '测试输入字段'");}
    private String sha() throws Exception {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(csv())));}
    private Path csv(){return output.resolve("train/ad_cvr_samples.csv");}
    private void keep() throws Exception {Files.createDirectories(csv().getParent());Files.writeString(csv(),"keep");}
    private List<String[]> rows() throws Exception {var lines=Files.readAllLines(csv());assertEquals("converted,delay_days,elapsed_days,user_id,item_id,category,item_pop_norm,item_avg_rating,user_act_norm,user_avg_rating,user_cat_affinity,split",lines.getFirst());return lines.stream().skip(1).map(s->s.split(",",-1)).toList();}
    private void run(String mode,String...args)throws Exception{var r=invoke(mode,args);assertEquals(0,r.status,r.log);}
    private void fail(String mode,String error,String...args)throws Exception{var r=invoke(mode,args);assertNotEquals(0,r.status,r.log);assertTrue(r.log.contains(error),r.log);}
    private record Result(int status,String log){}
    private Result invoke(String mode,String...args)throws Exception {
        var cmd=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),AdCvrSamplesFixture.class.getName(),url(),ONE.getJdbcUrl(),ZERO.getUsername(),ZERO.getPassword(),mode));
        cmd.addAll(Arrays.asList(args));Path log=Files.createTempFile(output,"cvr-",".log");var p=new ProcessBuilder(cmd).directory(output.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue(p.waitFor(45,TimeUnit.SECONDS),"CVR子进程超时");return new Result(p.exitValue(),Files.readString(log));}finally{if(p.isAlive())p.destroyForcibly();}
    }
}
