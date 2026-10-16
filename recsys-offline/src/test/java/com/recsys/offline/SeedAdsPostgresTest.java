package com.recsys.offline;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;


import com.recsys.offline.persistence.mapper.primary.SeedItemMapper;
import com.recsys.offline.persistence.mapper.addb.SeedAdVectorMapper;
import com.recsys.offline.persistence.mapper.derived.SeedItemVectorMapper;
import com.recsys.offline.persistence.mapper.sharded.SeedAdMapper;
import com.recsys.common.ad.BidwordInvCodec;
import com.recsys.common.constant.RedisKeys;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.junit.jupiter.api.*;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 两个真实分片、独立主/广告/派生schema和独占Redis保护种子四源路径。 */
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
@Testcontainers
class SeedAdsPostgresTest {
    @Container static final PostgreSQLContainer<?> ZERO = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Container static final PostgreSQLContainer<?> ONE = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    static final List<String> GOVERNANCE_TABLES=List.of("advertiser_catalog_schema_history","catalog_shard_control",
            "catalog_advertiser_guard","catalog_identity","catalog_aggregate","catalog_command","catalog_step","catalog_outbox","catalog_task_item");
    static JdbcTemplate zero, one, primary, adDb, derived;
    static DataSource sharded;
    static LettuceConnectionFactory redisFactory;
    static StringRedisTemplate redis;
    static SeedAdsJob job;
    static AnnotationConfigApplicationContext context;

    @BeforeAll static void prepare() throws Exception {
        HikariDataSource ds0 = shardSource(ZERO), ds1 = shardSource(ONE);
        zero = new JdbcTemplate(ds0); one = new JdbcTemplate(ds1);
        for (var db : List.of(zero, one)) {
            create(db, "advertiser", "advertiser_id BIGINT PRIMARY KEY,name TEXT,daily_budget DOUBLE PRECISION,status TEXT");
            create(db, "ad", "ad_id BIGINT PRIMARY KEY,advertiser_id BIGINT,item_id BIGINT,title TEXT,landing_url TEXT,quality_score DOUBLE PRECISION,status TEXT,optimization_type TEXT,target_cpa DOUBLE PRECISION");
            create(db, "bidword", "id BIGINT PRIMARY KEY,ad_id BIGINT,keyword TEXT,match_type TEXT,bid DOUBLE PRECISION,bid_mode TEXT");
            create(db, "ad_creative", "creative_id BIGINT PRIMARY KEY,ad_id BIGINT,title TEXT,landing_url TEXT,status TEXT");
            create(db,"catalog_shard_control","shard_id smallint PRIMARY KEY,mode text,source_id uuid,topology_version text,initializing_command_id uuid,initialization_ready boolean,stop_reason text,cluster_id text,topic_id text,topic_name text,writer_manifest jsonb DEFAULT '{}'");
            create(db,"advertiser_catalog_schema_history","shard_id smallint,version integer,script_sha256 text");
            int shard=db==zero?0:1;
            db.update("INSERT INTO catalog_shard_control(shard_id,mode,initialization_ready) VALUES(?,'LEGACY',false)",shard);
            // 这些表仅验证安装存在性；业务和Redis仍由真实原SeedAdsJob完整执行。
            for(String table:GOVERNANCE_TABLES.subList(2,GOVERNANCE_TABLES.size()))create(db,table,"fixture_marker bigint");
            db.update("INSERT INTO advertiser_catalog_schema_history VALUES(?,1,?)",shard,"0".repeat(64));

        }
        zero.execute("CREATE EXTENSION vector; CREATE SCHEMA offline_main; CREATE SCHEMA offline_ad; CREATE SCHEMA offline_derived");
        primary = schema("offline_main"); adDb = schema("offline_ad"); derived = schema("offline_derived");
        create(primary, "item", "item_id BIGINT PRIMARY KEY,title TEXT,category TEXT");
        create(primary, "item_local", "item_id BIGINT PRIMARY KEY,title TEXT,category TEXT");
        create(adDb, "ad_embedding", "ad_id BIGINT PRIMARY KEY,embedding vector(3),model TEXT");
        create(adDb, "ad_event", "id BIGINT PRIMARY KEY");
        create(derived, "item_embedding", "item_id BIGINT PRIMARY KEY,embedding vector(3),model TEXT");
        String yaml;
        try (var in = SeedAdsPostgresTest.class.getResourceAsStream("/sharding.yaml")) {
            yaml = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
        sharded = YamlShardingSphereDataSourceFactory.createDataSource(Map.of("ds_0", ds0, "ds_1", ds1),
                yaml.substring(yaml.indexOf("\nrules:") + 1).getBytes(StandardCharsets.UTF_8));
        redisFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redisFactory.afterPropertiesSet(); redisFactory.start();
        redis = new StringRedisTemplate(redisFactory);
        context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("dataSource", primary.getDataSource());
        context.getBeanFactory().registerSingleton("adDbDataSource", adDb.getDataSource());
        context.getBeanFactory().registerSingleton("derivedDbDataSource", derived.getDataSource());
        context.getBeanFactory().registerSingleton("adShardingDataSource", sharded);
        context.getBeanFactory().registerSingleton("stringRedisTemplate", redis);
        context.getBeanFactory().registerSingleton("catalogSeedAdmission", new com.recsys.offline.persistence.CatalogSeedAdmission(ds0,ds1));
        context.register(OfflineAdShardingConfig.class.getAnnotation(Import.class).value());
        context.register(SeedAdsJob.class);
        context.refresh();
        job = context.getBean(SeedAdsJob.class);
    }

    @AfterAll static void close() throws Exception {
        if (context != null) context.close();
        if (redisFactory != null) redisFactory.destroy();
        if (sharded instanceof AutoCloseable closeable) closeable.close();
    }

    @BeforeEach void reset() {
        for (var db : List.of(zero, one)) { db.execute("TRUNCATE advertiser,ad,bidword,ad_creative");db.update("UPDATE catalog_shard_control SET mode='LEGACY',source_id=NULL,topology_version=NULL,initializing_command_id=NULL,initialization_ready=false,stop_reason=NULL,cluster_id=NULL,topic_id=NULL,topic_name=NULL,writer_manifest='{}'::jsonb"); }
        primary.execute("TRUNCATE item,item_local"); adDb.execute("TRUNCATE ad_embedding,ad_event"); derived.execute("TRUNCATE item_embedding");
        try (var connection = redisFactory.getConnection()) { connection.serverCommands().flushDb(); }
        primary.execute("INSERT INTO item VALUES(10,'Space movie (1995)','Drama'),(20,'Action movie','Action'),(30,NULL,NULL),(40,'Forest adventure','Comedy'); INSERT INTO item_local VALUES(1000,'Replica story','Replica')");
        derived.execute("INSERT INTO item_embedding VALUES(10,'[1,0,0]','first'),(20,'[0,1,0]','second')");
    }

    @Test void reliableSecondPhysicalShardBlocksBeforeEveryClearSideEffect() {
        job.run(new DefaultApplicationArguments("--ads=2","--advertisers=2"));
        var before=ads();var beforeIndex=invertedIndex();
        adDb.execute("INSERT INTO ad_event VALUES(999)");
        one.update("UPDATE catalog_shard_control SET mode='ACTIVE'");
        var failure=assertThrows(PublicErrorException.class,()->job.run(new DefaultApplicationArguments("--clear","--ads=2","--advertisers=2")));
        assertEquals(ErrorCode.CATALOG_NOT_READY,failure.errorCode());assertEquals(before,ads());assertEquals(beforeIndex,invertedIndex());
        assertEquals(1,adDb.queryForObject("SELECT count(*) FROM ad_event WHERE id=999",Integer.class));
    }

    @Test void missingPhysicalControlIsUnknownAndDoesNotEraseExistingData() {
        job.run(new DefaultApplicationArguments("--ads=2","--advertisers=2"));var before=ads();var beforeIndex=invertedIndex();
        one.execute("ALTER TABLE catalog_shard_control RENAME TO catalog_probe_absent");
        try {
            var failure=assertThrows(PublicErrorException.class,()->job.run(new DefaultApplicationArguments("--clear")));
            assertEquals(ErrorCode.CATALOG_NOT_READY,failure.errorCode());assertEquals(before,ads());assertEquals(beforeIndex,invertedIndex());
        } finally {one.execute("ALTER TABLE catalog_probe_absent RENAME TO catalog_shard_control");}
    }

    @Test void noNewMigrationsOnBothPhysicalPgPreservesOriginalSeedClearVectorsAndRedis() {
        withGovernanceAbsent(()->assertOriginalSeedRebuild());
    }

    @Test void installedButMissingControlRowRefusesBeforeAnyEffect() {
        job.run(new DefaultApplicationArguments("--ads=2","--advertisers=2"));
        one.update("DELETE FROM catalog_shard_control");
        try {assertAdmissionRefused(job);}finally{one.update("INSERT INTO catalog_shard_control(shard_id,mode,initialization_ready) VALUES(1,'LEGACY',false)");}
    }

    @Test void installedMultipleWrongIdentityAndHalfRegisteredControlRefuseBeforeAnyEffect() {
        job.run(new DefaultApplicationArguments("--ads=2","--advertisers=2"));
        for(String change:List.of("UPDATE catalog_shard_control SET shard_id=0",
                "INSERT INTO catalog_shard_control(shard_id,mode,initialization_ready) VALUES(0,'LEGACY',false)",
                "UPDATE catalog_shard_control SET mode='QUIESCED'", "UPDATE catalog_shard_control SET source_id='00000000-0000-0000-0000-000000000001'",
                "UPDATE catalog_shard_control SET cluster_id='partly-bound'", "UPDATE catalog_shard_control SET topic_id='partly-bound'",
                "UPDATE catalog_shard_control SET writer_manifest='{\"writer\":1}'::jsonb", "UPDATE catalog_shard_control SET initialization_ready=true")) {
            one.execute(change);
            try {assertAdmissionRefused(job);}finally{one.update("DELETE FROM catalog_shard_control");one.update("INSERT INTO catalog_shard_control(shard_id,mode,initialization_ready) VALUES(1,'LEGACY',false)");}
        }
    }

    @Test void unreachableSecondPgRefusesBeforeClearOrRedis() throws Exception {
        job.run(new DefaultApplicationArguments("--ads=2","--advertisers=2"));
        int port;try(var socket=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
        var env=new org.springframework.mock.env.MockEnvironment().withProperty("PG_JDBC_URL",ZERO.getJdbcUrl())
                .withProperty("PG_DS1_JDBC_URL","jdbc:postgresql://127.0.0.1:"+port+"/missing")
                .withProperty("PG_USER",ZERO.getUsername()).withProperty("PG_PASSWORD",ZERO.getPassword());
        var denied=withAdmission(new com.recsys.offline.persistence.CatalogSeedAdmission(env));
        long start=System.nanoTime();assertAdmissionRefused(denied);
        assertTrue(java.time.Duration.ofNanos(System.nanoTime()-start).compareTo(java.time.Duration.ofSeconds(6))<0);
    }

    @Test void twoUrlAliasesOfSameUnmigratedDatabaseRefuseBeforeAnyEffect() {
        job.run(new DefaultApplicationArguments("--ads=2","--advertisers=2"));
        var alias=new DriverManagerDataSource(ZERO.getJdbcUrl().replace("localhost","127.0.0.1"),ZERO.getUsername(),ZERO.getPassword());
        var denied=withAdmission(new com.recsys.offline.persistence.CatalogSeedAdmission(zero.getDataSource(),alias));
        withGovernanceAbsent(()->assertAdmissionRefused(denied));
    }

    @Test void ordinaryRoleCanProveAbsentButCannotTreatDeniedControlReadAsAbsent() {
        job.run(new DefaultApplicationArguments("--ads=2","--advertisers=2"));
        for(var db:List.of(zero,one))db.execute("CREATE ROLE catalog_probe_reader LOGIN PASSWORD 'isolated-test-only'");
        var source0=new DriverManagerDataSource(ZERO.getJdbcUrl(),"catalog_probe_reader","isolated-test-only");
        var source1=new DriverManagerDataSource(ONE.getJdbcUrl(),"catalog_probe_reader","isolated-test-only");
        var reader=new com.recsys.offline.persistence.CatalogSeedAdmission(source0,source1);
        try {
            withGovernanceAbsent(reader::requireLegacy);
            assertAdmissionRefused(withAdmission(reader));
        } finally {for(var db:List.of(zero,one))db.execute("DROP ROLE catalog_probe_reader");}
    }

    private void withGovernanceAbsent(Runnable operation) {
        for(var db:List.of(zero,one))for(String table:GOVERNANCE_TABLES)db.execute("ALTER TABLE "+table+" RENAME TO absent_"+table);
        try {operation.run();}finally{for(var db:List.of(zero,one))for(String table:GOVERNANCE_TABLES)db.execute("ALTER TABLE absent_"+table+" RENAME TO "+table);}
    }

    private SeedAdsJob withAdmission(com.recsys.offline.persistence.CatalogSeedAdmission admission) {
        return new SeedAdsJob(context.getBean(SeedItemMapper.class),context.getBean(SeedAdVectorMapper.class),
                context.getBean(SeedItemVectorMapper.class),context.getBean(SeedAdMapper.class),redis,admission);
    }

    private void assertAdmissionRefused(SeedAdsJob candidate) {
        var before=ads();var beforeIndex=invertedIndex();var beforeVectors=adDb.queryForList("SELECT ad_id,embedding::text,model FROM ad_embedding ORDER BY ad_id");
        adDb.update("INSERT INTO ad_event VALUES(999) ON CONFLICT DO NOTHING");
        var failure=assertThrows(PublicErrorException.class,()->candidate.run(new DefaultApplicationArguments("--clear","--ads=2","--advertisers=2")));
        assertEquals(ErrorCode.CATALOG_NOT_READY,failure.errorCode());assertEquals(before,ads());assertEquals(beforeIndex,invertedIndex());
        assertEquals(beforeVectors,adDb.queryForList("SELECT ad_id,embedding::text,model FROM ad_embedding ORDER BY ad_id"));
        assertEquals(1,adDb.queryForObject("SELECT count(*) FROM ad_event WHERE id=999",Integer.class));
    }

    @Test void physicalRoutingSnowflakeVectorsRedisAndClearRebuildRemainExact() {assertOriginalSeedRebuild();}

    private void assertOriginalSeedRebuild() {
        adDb.execute("INSERT INTO ad_event VALUES(999); INSERT INTO ad_embedding VALUES(999,'[0,0,1]','obsolete')");
        redis.opsForZSet().add(RedisKeys.bidwordInv("obsolete"), "obsolete", 1);
        redis.opsForValue().set("unrelated:key", "keep");
        var args = new DefaultApplicationArguments("--ads=4", "--advertisers=3", "--creatives-per-ad=2", "--seed=42", "--clear");
        job.run(args);
        assertEquals(0, adDb.queryForObject("SELECT count(*) FROM ad_event", Integer.class));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(RedisKeys.bidwordInv("obsolete"))));
        assertEquals("keep", redis.opsForValue().get("unrelated:key"));
        assertEquals(1, zero.queryForObject("SELECT count(*) FROM advertiser", Integer.class));
        assertEquals(2, one.queryForObject("SELECT count(*) FROM advertiser", Integer.class));
        assertEquals(1, zero.queryForObject("SELECT count(*) FROM ad", Integer.class));
        assertEquals(3, one.queryForObject("SELECT count(*) FROM ad", Integer.class));
        var ads = ads();
        assertEquals(4, ads.size());
        for (var ad : ads) {
            long id = ((Number) ad.get("ad_id")).longValue();
            long owner = ((Number) ad.get("advertiser_id")).longValue();
            assertEquals((id - 1) % 3 + 1, owner);
            assertEquals(1, physical(owner).queryForObject("SELECT count(*) FROM ad WHERE ad_id=?", Integer.class, id));
            assertEquals("https://example.com/ad/" + id, ad.get("landing_url"));
            assertEquals("active", ad.get("status"));
            double quality = ((Number) ad.get("quality_score")).doubleValue();
            assertTrue(quality >= 0.7 && quality <= 1.0);
            assertEquals(id <= 3 ? "OCPC" : "CPC", ad.get("optimization_type"));
            if (id == 4) assertNull(ad.get("target_cpa"));
            else assertTrue(((Number) ad.get("target_cpa")).doubleValue() >= 5);
            var creatives = physical(id).queryForList("SELECT creative_id,title FROM ad_creative WHERE ad_id=? ORDER BY title", id);
            assertEquals(2, creatives.size());
            for (var creative : creatives) assertTrue(((Number) creative.get("creative_id")).longValue() > 0);
            assertEquals(2, physical(id).queryForObject("SELECT count(DISTINCT creative_id) FROM ad_creative WHERE ad_id=?", Integer.class, id));
            for (var bidword : physical(id).queryForList("SELECT * FROM bidword WHERE ad_id=?", id)) {
                long bidwordId = ((Number) bidword.get("id")).longValue();
                String keyword = (String) bidword.get("keyword");
                String member = BidwordInvCodec.encode(id, bidwordId, ((Number) ad.get("item_id")).longValue(), owner, quality);
                assertEquals(((Number) bidword.get("bid")).doubleValue(), redis.opsForZSet().score(RedisKeys.bidwordInv(keyword), member));
                assertEquals("CPC", bidword.get("bid_mode"));
                assertEquals(keyword.contains(" ") ? "PHRASE" : "EXACT", bidword.get("match_type"));
            }
        }
        assertEquals(2, adDb.queryForObject("SELECT count(*) FROM ad_embedding", Integer.class));
        for (var row : adDb.queryForList("SELECT ad_id,embedding::text AS v,model FROM ad_embedding")) {
            var ad = ads.stream().filter(a -> a.get("ad_id").equals(row.get("ad_id"))).findFirst().orElseThrow();
            var source = derived.queryForMap("SELECT embedding::text AS v,model FROM item_embedding WHERE item_id=?", ad.get("item_id"));
            assertEquals(source.get("v"), row.get("v")); assertEquals(source.get("model"), row.get("model"));
        }
        var beforeRedis = invertedIndex();
        job.run(args);
        assertEquals(ads, ads());
        assertEquals(beforeRedis, invertedIndex());
    }

    @Test void replicaArgumentReadsOnlyReplicaAndKeepsMissingVectorsAbsent() {
        job.run(new DefaultApplicationArguments("--ads=1", "--advertisers=1", "--item-table=item_local"));
        assertEquals(1000L, one.queryForObject("SELECT item_id FROM ad WHERE ad_id=1", Long.class));
        assertEquals(0, adDb.queryForObject("SELECT count(*) FROM ad_embedding", Integer.class));
    }

    @Test void repeatWithoutClearPreservesOriginalDuplicateFailure() {
        var args = new DefaultApplicationArguments("--ads=1", "--advertisers=1");
        job.run(args);
        var before = ads();
        assertThrows(DuplicateKeyException.class, () -> job.run(args));
        assertEquals(before, ads());
    }

    @Test void emptyCatalogDoesNotCreateAdvertisersOrRedisEntries() {
        primary.execute("TRUNCATE item");
        assertEquals(ErrorCode.JOB_GENERATION_INPUT_EMPTY, assertThrows(PublicErrorException.class, () ->
                job.run(new DefaultApplicationArguments("--ads=4", "--advertisers=3"))).errorCode());
        assertEquals(0, zero.queryForObject("SELECT count(*) FROM advertiser", Integer.class));
        assertEquals(0, one.queryForObject("SELECT count(*) FROM advertiser", Integer.class));
        assertTrue(invertedIndex().isEmpty());
    }

    @Test void nullVectorProjectionSkipsBothEntirelyNullAndModelOnlyRows() {
        derived.execute("INSERT INTO item_embedding VALUES(30,NULL,NULL),(40,NULL,'model-only')");
        job.run(new DefaultApplicationArguments("--ads=4", "--advertisers=3"));
        assertEquals(4, ads().size());
        assertEquals(2, adDb.queryForObject("SELECT count(*) FROM ad_embedding", Integer.class));
    }

    @Test void catalogSourceRejectsUntrustedIdentifierBeforeAnyWrite() {
        assertThrows(PublicErrorException.class,
                () -> job.run(new DefaultApplicationArguments("--item-table=item;DROP TABLE ad")));
        assertTrue(ads().isEmpty());
        assertTrue(invertedIndex().isEmpty());
    }

    @Test void invalidParametersAndSourcesAreRejectedBeforeClear() {
        job.run(new DefaultApplicationArguments("--ads=2", "--advertisers=2"));
        adDb.execute("INSERT INTO ad_event VALUES(999)");
        var original=ads();var originalRedis=invertedIndex();
        for (String option : List.of("--ads=0", "--ads=-1", "--advertisers=0", "--creatives-per-ad=0", "--seed=private", "--ads")) {
            assertEquals(ErrorCode.JOB_INVALID_ARGUMENT, assertThrows(PublicErrorException.class, () ->
                    job.run(new DefaultApplicationArguments("--clear", option))).errorCode());
        }
        assertThrows(PublicErrorException.class, () -> job.run(new DefaultApplicationArguments("--clear", "--item-table=private")));
        assertEquals(original, ads());assertEquals(originalRedis, invertedIndex());
        assertEquals(1, adDb.queryForObject("SELECT count(*) FROM ad_event", Integer.class));
    }

    @Test void actualShardTruncateFailureKeepsPartialCleanupButStopsGeneration(org.springframework.boot.test.system.CapturedOutput output) {
        job.run(new DefaultApplicationArguments("--ads=2", "--advertisers=2"));
        adDb.execute("INSERT INTO ad_event VALUES(999)");
        create(zero, "seed_clear_guard", "id BIGINT PRIMARY KEY,bidword_id BIGINT REFERENCES bidword(id)");
        int before=output.getAll().length();
        try {
            assertEquals(ErrorCode.JOB_SEED_CLEAR_FAILED, assertThrows(PublicErrorException.class, () ->
                    job.run(new DefaultApplicationArguments("--clear", "--ads=2", "--advertisers=2"))).errorCode());
            assertTrue(zero.queryForObject("SELECT count(*) FROM bidword", Integer.class) > 0);
            assertTrue(ads().isEmpty());assertTrue(invertedIndex().isEmpty());
            assertEquals(0, adDb.queryForObject("SELECT count(*) FROM ad_event", Integer.class));
            assertEquals(0, zero.queryForObject("SELECT count(*) FROM advertiser", Integer.class));
            assertEquals(0, one.queryForObject("SELECT count(*) FROM advertiser", Integer.class));
            String failedRun=output.getAll().substring(before);
            assertFalse(failedRun.contains("已清空广告相关表"));assertFalse(failedRun.contains("seed-ads 完成"));
        } finally {
            zero.execute("DROP TABLE seed_clear_guard");
        }
        job.run(new DefaultApplicationArguments("--clear", "--ads=2", "--advertisers=2"));
        assertEquals(2, ads().size());
    }

    @Test void realRedisCleanupDenialStopsSeedingAfterDatabaseCleanup() throws Exception {
        job.run(new DefaultApplicationArguments("--ads=2", "--advertisers=2"));
        var oldIndex=invertedIndex();assertFalse(oldIndex.isEmpty());
        var acl=REDIS.execInContainer("redis-cli", "ACL", "SETUSER", "seed-clear-test", "on", ">test-only-password", "+@all", "-keys", "~*");
        assertEquals(0, acl.getExitCode());
        var config=new org.springframework.data.redis.connection.RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        config.setUsername("seed-clear-test");config.setPassword("test-only-password");
        var factory=new LettuceConnectionFactory(config);factory.afterPropertiesSet();factory.start();
        try {
            var restricted=new SeedAdsJob(context.getBean(SeedItemMapper.class), context.getBean(SeedAdVectorMapper.class),
                    context.getBean(SeedItemVectorMapper.class), context.getBean(SeedAdMapper.class), new StringRedisTemplate(factory), context.getBean(com.recsys.offline.persistence.CatalogSeedAdmission.class));
            var error=assertThrows(PublicErrorException.class, () -> restricted.run(new DefaultApplicationArguments("--clear", "--ads=2", "--advertisers=2")));
            assertEquals(ErrorCode.JOB_SEED_CLEAR_FAILED, error.errorCode());
            assertTrue(ads().isEmpty());assertEquals(oldIndex, invertedIndex());
        } finally { factory.destroy(); }
        job.run(new DefaultApplicationArguments("--clear", "--ads=2", "--advertisers=2"));
        assertEquals(2, ads().size());
    }

    @Test void emptyInputAfterClearReportsThatOldDataMayAlreadyBeRemoved() {
        job.run(new DefaultApplicationArguments("--ads=2", "--advertisers=2"));
        primary.execute("TRUNCATE item");
        var failure=assertThrows(PublicErrorException.class, () -> job.run(new DefaultApplicationArguments("--clear")));
        assertEquals(ErrorCode.JOB_GENERATION_INPUT_EMPTY, failure.errorCode());
        assertTrue(failure.getMessage().contains("旧数据可能已清除"));
        assertTrue(ads().isEmpty());assertTrue(invertedIndex().isEmpty());
    }

    private static List<Map<String,Object>> ads() {
        var rows = new ArrayList<>(zero.queryForList("SELECT * FROM ad")); rows.addAll(one.queryForList("SELECT * FROM ad"));
        rows.sort(Comparator.comparingLong(row -> ((Number) row.get("ad_id")).longValue())); return rows;
    }

    private static Map<String,Object> invertedIndex() {
        Map<String,Object> result = new TreeMap<>();
        for (String key : Objects.requireNonNull(redis.keys("bidword:inv:*"))) result.put(key, redis.opsForZSet().rangeWithScores(key, 0, -1));
        return result;
    }

    private static JdbcTemplate physical(long key) { return key % 2 == 0 ? zero : one; }

    private static HikariDataSource shardSource(PostgreSQLContainer<?> pg) {
        var ds = new HikariDataSource(); ds.setJdbcUrl(pg.getJdbcUrl()); ds.setUsername(pg.getUsername()); ds.setPassword(pg.getPassword()); ds.setMaximumPoolSize(3); return ds;
    }

    private static JdbcTemplate schema(String schema) {
        String url = ZERO.getJdbcUrl() + (ZERO.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
        return new JdbcTemplate(new DriverManagerDataSource(url, ZERO.getUsername(), ZERO.getPassword()));
    }

    /** 仅独占容器建表，所有列带注释。 */
    private static void create(JdbcTemplate db, String table, String columns) {
        db.execute("CREATE TABLE " + table + "(" + columns + ")");
        db.execute("COMMENT ON TABLE " + table + " IS '广告种子迁移独占测试'");
        for (String column : columns.split(",")) db.execute("COMMENT ON COLUMN " + table + "." + column.strip().split(" ")[0] + " IS '测试源字段'");
    }
}
