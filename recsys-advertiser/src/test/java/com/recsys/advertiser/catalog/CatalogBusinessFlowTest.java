package com.recsys.advertiser.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.advertiser.*;
import com.recsys.advertiser.authz.AdvertiserAuthz;
import com.recsys.advertiser.dto.*;
import com.recsys.advertiser.persistence.mapper.primary.*;
import com.recsys.advertiser.persistence.mapper.derived.AdvertiserItemEmbeddingMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import com.recsys.common.ad.VersionedAdCatalogEvent;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static com.recsys.advertiser.catalog.CatalogPhysicalTransactionTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实Controller和产品编排/Mapper至双PG/Kafka；本类MockMvc不冒称独立HTTP服务器或进程故障验收。 */
@Testcontainers
@Tag("catalog-reliability")
class CatalogBusinessFlowTest {
    @Container static final PostgreSQLContainer<?> PG0=ZERO,PG1=ONE;
    @Container static final KafkaFixture BROKER=KAFKA;
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static CatalogLocalMapper local;
    static CatalogBusinessMapper business;
    static CatalogTaskMapper tasks;
    static AdvertiserItemEmbeddingMapper vectors;
    static LettuceConnectionFactory redisConnection;
    static StringRedisTemplate redis;
    final ObjectMapper object=new ObjectMapper().findAndRegisterModules();
    CatalogCommandRepository commands;CatalogCommandService service;CatalogEnrichment enrichment;CatalogJson json;CatalogWriteRouter router;
    CatalogOutboxRepository events;MockMvc http;

    @BeforeAll static void startBusiness() throws Exception {
        CatalogPhysicalTransactionTest.start();
        var factory=SpringSqlSessions.create(sharded,"classpath*:mapper/advertiser/primary/Catalog*.xml");
        var session=new SqlSessionTemplate(factory);local=session.getMapper(CatalogLocalMapper.class);business=session.getMapper(CatalogBusinessMapper.class);tasks=session.getMapper(CatalogTaskMapper.class);
        vectors=new SqlSessionTemplate(SpringSqlSessions.create(ds0,"classpath*:mapper/advertiser/derived/AdvertiserItemEmbeddingMapper.xml")).getMapper(AdvertiserItemEmbeddingMapper.class);
        redisConnection=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));redisConnection.afterPropertiesSet();redisConnection.start();redis=new StringRedisTemplate(redisConnection);
    }
    @BeforeEach void prepare() {
        new CatalogPhysicalTransactionTest().arrangeExclusiveDatabase();
        zero.execute("TRUNCATE catalog_aggregate");one.execute("TRUNCATE catalog_aggregate");
        zero.execute("TRUNCATE catalog_task_item");one.execute("TRUNCATE catalog_task_item");
        one.update("INSERT INTO catalog_aggregate(shard_id,ad_id,advertiser_id,reserved_version,last_materialized_version) VALUES(1,102,1,1,1)");
        one.update("UPDATE catalog_advertiser_guard SET active_command_id=NULL,active_command_home=NULL,active_command_scope=NULL");
        var tx=new CatalogLocalTransactions(sharded,local);json=new CatalogJson(object);var codec=new CatalogSnapshotCodec(object);
        commands=new CatalogCommandRepository(local,tx);events=new CatalogOutboxRepository(local,tx,codec);
        var steps=new CatalogBusinessSteps(business,local,new CatalogStepRepository(local,tx,object),commands,events,json,codec);
        var authz=new AdvertiserAuthz("disabled",new StaticListableBeanFactory().getBeanProvider(com.lrj.authz.protocol.AuthzEngine.class));
        enrichment=new CatalogEnrichment(vectors,redis);
        var fanout=new CatalogFanoutService(local,tasks,business,tx,steps,commands,events,enrichment,json,codec);
        var batches=new CatalogStatusBatchService(local,tasks,tx,commands,json,SOURCE,true,true);
        service=new CatalogCommandService(commands,steps,enrichment,json,authz,null,fanout,batches);
        router=new CatalogWriteRouter(SOURCE,true,commands,service,business,json,authz);
        // 可靠分支绝不可调用legacy吞错同步；null一旦误落回便会使测试失败。
        var ads=new AdController(null);ads.setCatalogRouter(router);var words=new BidwordController(null);words.setCatalogRouter(router);
        var creatives=new CreativeController(null);creatives.setCatalogRouter(router);var roots=new AdvertiserController(null);roots.setCatalogRouter(router);
        http=MockMvcBuilders.standaloneSetup(ads,words,creatives,roots,new CatalogCommandController(router,new CatalogCommandQuery(local,tasks)))
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(object)).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("original-owner","unused",List.of(new SimpleGrantedAuthority("ROLE_ADVERTISER"))));
    }
    @AfterEach void stopEnrichment(){enrichment.close();SecurityContextHolder.clearContext();}
    @AfterAll static void closeBusiness() throws Exception {if(redisConnection!=null)redisConnection.destroy();CatalogPhysicalTransactionTest.close();}

    @Test void actualAdControllerCreatesInitialChildrenReviewsMutatesAndDeletesWithOriginalRetry() throws Exception {
        UUID create=UUID.randomUUID();
        var body=new AdUpsert(10L,"正常创意",null,1.0,"active","CPC",null,7L,List.of(new BidwordUpsert(" Shoes ","EXACT",2.5,"CPC")));
        var response=http.perform(post("/api/advertiser/1/ad").header(CatalogWriteRouter.COMMAND_HEADER,create).header(CatalogWriteRouter.OWNER_HEADER,"1")
                .contentType("application/json").content(object.writeValueAsBytes(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        long ad=object.readTree(response).get("adId").longValue();assertThat(ad).isPositive();
        assertThat(object.readTree(response).get("reviewStatus").asText()).isEqualTo("pending_review");
        assertThat(object.readTree(response).get("bidwords").get(0).get("keyword").asText()).isEqualTo("shoes");
        var first=lastEvent(ad);assertThat(first.aggregateVersion()).isOne();assertThat(first.servable()).isFalse();
        http.perform(post("/api/advertiser/1/ad").header(CatalogWriteRouter.COMMAND_HEADER,create).header(CatalogWriteRouter.OWNER_HEADER,"1")
                .contentType("application/json").content(object.writeValueAsBytes(body))).andExpect(status().isOk()).andExpect(content().json(new String(response,java.nio.charset.StandardCharsets.UTF_8)));
        http.perform(post("/api/advertiser/ad/"+ad+"/review").param("decision","approve")).andExpect(status().isOk());
        assertThat(lastEvent(ad).servable()).isTrue();
        http.perform(put("/api/advertiser/ad/"+ad).contentType("application/json").content("{\"audienceId\":0,\"title\":\"修改标题\"}")).andExpect(status().isOk());
        assertThat(lastEvent(ad).ad().audienceId()).isNull();assertThat(lastEvent(ad).ad().reviewStatus()).isEqualTo("approved");
        UUID delete=UUID.randomUUID();
        for(int retry=0;retry<2;retry++)http.perform(delete("/api/advertiser/ad/"+ad).header(CatalogWriteRouter.COMMAND_HEADER,delete)
                .header(CatalogWriteRouter.OWNER_HEADER,"1")).andExpect(status().isOk()).andExpect(jsonPath("adId").value(ad));
        var deleted=lastEvent(ad);assertThat(deleted.lifecycle()).isEqualTo(VersionedAdCatalogEvent.Lifecycle.DELETED);
        assertThat(deleted.aggregateVersion()).isEqualTo(4);assertThat(business.ad(1,ad)).isNull();assertThat(business.bidwords(ad,0)).isEmpty();
        assertThat(one.queryForObject("SELECT active_command_id FROM catalog_advertiser_guard WHERE advertiser_id=1",UUID.class)).isNull();
        try(var producer=new CatalogKafkaPublisher(KAFKA.bootstrap(),kafkaIdentity.clusterId(),kafkaIdentity.topicId())) {
            new CatalogRelay(events,producer,SOURCE).advance(1);
        }
        assertThat(one.queryForObject("SELECT count(*) FROM catalog_outbox WHERE purpose='KAFKA' AND state='PUBLISHED'",Integer.class)).isEqualTo(4);
    }

    @Test void oppositeChildShardKeywordAndCreativeControllersPublishFullSnapshots() throws Exception {
        var word=http.perform(post("/api/advertiser/ad/102/bidword").contentType("application/json").content("{\"keyword\":\"car\",\"bid\":2}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();long wordId=object.readTree(word).get("id").longValue();
        assertThat(zero.queryForObject("SELECT count(*) FROM bidword WHERE ad_id=102",Integer.class)).isOne();
        http.perform(put("/api/advertiser/bidword/"+wordId).contentType("application/json").content("{\"bid\":3}")).andExpect(status().isOk());
        var creative=http.perform(post("/api/advertiser/ad/102/creative").contentType("application/json").content("{\"title\":\"创意标题\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();long creativeId=object.readTree(creative).get("creativeId").longValue();
        http.perform(put("/api/advertiser/creative/"+creativeId).contentType("application/json").content("{\"status\":\"paused\"}")).andExpect(status().isOk());
        var event=lastEvent(102);assertThat(event.creatives().getFirst().reviewStatus()).isEqualTo("approved");assertThat(event.creatives().getFirst().status()).isEqualTo("paused");
        UUID deletion=UUID.randomUUID();for(int i=0;i<2;i++)http.perform(delete("/api/advertiser/bidword/"+wordId).header(CatalogWriteRouter.COMMAND_HEADER,deletion).header(CatalogWriteRouter.OWNER_HEADER,"1"))
                .andExpect(status().isOk()).andExpect(jsonPath("bidwordId").value(wordId));
        http.perform(delete("/api/advertiser/creative/"+creativeId)).andExpect(status().isOk()).andExpect(jsonPath("creativeId").value(creativeId));
        event=lastEvent(102);assertThat(event.bidwords()).isEmpty();assertThat(event.creatives()).isEmpty();assertThat(event.aggregateVersion()).isEqualTo(7);
    }

    @Test void fixedHomeRootCreationAndNameBudgetKeepOriginalIdentityAndQuery() throws Exception {
        UUID id=UUID.randomUUID();String input="{\"name\":\"原广告主\",\"dailyBudget\":90}";
        var body=http.perform(post("/api/advertiser").header(CatalogWriteRouter.COMMAND_HEADER,id).contentType("application/json").content(input))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();long owner=object.readTree(body).get("advertiserId").longValue();
        assertThat(commands.find(CatalogProtocol.CommandKey.root(SOURCE,id)).advertiserId()).isEqualTo(owner);
        http.perform(post("/api/advertiser").header(CatalogWriteRouter.COMMAND_HEADER,id).contentType("application/json").content(input)).andExpect(status().isOk()).andExpect(jsonPath("advertiserId").value(owner));
        http.perform(get("/api/advertiser/catalog-commands/"+id)).andExpect(status().isOk()).andExpect(jsonPath("hasResult").value(true));
        redis.opsForValue().set(com.recsys.common.constant.RedisKeys.adBudget(owner,java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))),"1234");
        http.perform(put("/api/advertiser/"+owner).contentType("application/json").content("{\"name\":\"新名字\",\"dailyBudget\":100}"))
                .andExpect(status().isOk()).andExpect(jsonPath("spentToday").value(12.34));
        assertThat(business.root(owner).name()).isEqualTo("新名字");
    }

    private VersionedAdCatalogEvent lastEvent(long ad) {
        byte[] original=one.queryForObject("SELECT payload_bytes FROM catalog_outbox WHERE purpose='KAFKA' AND ad_id=? ORDER BY aggregate_version DESC LIMIT 1",byte[].class,ad);
        return new CatalogSnapshotCodec(object).decode(com.recsys.common.ad.CatalogEventCodec.key(ad),original);
    }
    @Test void rejectedCommandsRemainDefiniteErrorsAndInvalidRootDoesNotReserveIdentity() throws Exception {
        UUID invalid=UUID.randomUUID();
        for(int retry=0;retry<2;retry++)http.perform(put("/api/advertiser/ad/102/status").param("status","invalid")
                .header(CatalogWriteRouter.COMMAND_HEADER,invalid).header(CatalogWriteRouter.OWNER_HEADER,"1")).andExpect(status().isBadRequest());
        assertThat(commands.find(CatalogProtocol.CommandKey.advertiser(SOURCE,1,invalid)).state()).isEqualTo("REJECTED");
        UUID fanout=UUID.randomUUID();for(int retry=0;retry<2;retry++)http.perform(put("/api/advertiser/1").contentType("application/json").content("{\"status\":\"unsupported\"}")
                .header(CatalogWriteRouter.COMMAND_HEADER,fanout).header(CatalogWriteRouter.OWNER_HEADER,"1")).andExpect(status().isBadRequest());
        assertThat(business.root(1).status()).isEqualTo("active");
        UUID root=UUID.randomUUID();http.perform(post("/api/advertiser").contentType("application/json").content("{\"name\":\"  \",\"dailyBudget\":1}")
                .header(CatalogWriteRouter.COMMAND_HEADER,root)).andExpect(status().isBadRequest());
        assertThat(commands.find(CatalogProtocol.CommandKey.root(SOURCE,root))).isNull();
        for(var db:List.of(zero,one))assertThat(db.queryForObject("SELECT count(*) FROM catalog_identity WHERE command_id=?",Integer.class,root)).isZero();
    }
    @Test void initialWordsRollbackTogetherAndOriginalCreatedAdResumesWithoutSecondIdentity() throws Exception {
        for(var db:List.of(zero,one))db.execute("ALTER TABLE bidword ADD CONSTRAINT catalog_test_word_fail CHECK(keyword<>'fail-word')");
        UUID id=UUID.randomUUID();String body="{\"itemId\":10,\"title\":\"初始词原子\",\"bidwords\":[{\"keyword\":\"first-word\",\"bid\":1},{\"keyword\":\"fail-word\",\"bid\":2}]}";
        long ad;
        try {
            http.perform(post("/api/advertiser/1/ad").contentType("application/json").content(body).header(CatalogWriteRouter.COMMAND_HEADER,id).header(CatalogWriteRouter.OWNER_HEADER,"1")).andExpect(status().isServiceUnavailable());
            var command=commands.find(CatalogProtocol.CommandKey.advertiser(SOURCE,1,id));assertThat(command.phase()).isEqualTo("CHILDREN");
            ad=json.decode(command.frozenBytes(),CatalogFragments.Frozen.class).owner().desired().adId();
            assertThat(business.ad(1,ad)).isNotNull();assertThat(business.bidwords(ad,0)).isEmpty();
            var child=ad%2==0?zero:one;assertThat(child.queryForObject("SELECT count(*) FROM catalog_outbox WHERE command_id=? AND step_id='CHILDREN'",Integer.class,id)).isZero();
            http.perform(put("/api/advertiser/ad/102").contentType("application/json").content("{\"title\":\"不能交错\"}")).andExpect(status().isConflict());
        } finally {for(var db:List.of(zero,one))db.execute("ALTER TABLE bidword DROP CONSTRAINT catalog_test_word_fail");}
        Thread.sleep(1300);
        var result=http.perform(post("/api/advertiser/1/ad").contentType("application/json").content(body).header(CatalogWriteRouter.COMMAND_HEADER,id).header(CatalogWriteRouter.OWNER_HEADER,"1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(object.readTree(result).get("adId").longValue()).isEqualTo(ad);assertThat(business.bidwords(ad,0)).hasSize(2);
        assertThat(one.queryForObject("SELECT count(*) FROM catalog_step WHERE command_id=? AND step_id='PREPARE'",Integer.class,id)).isOne();
    }
    @Test void derivedRealRowsPreserveUnknownAndNewItemBindingWithoutChangingOldEvent() throws Exception {
        String vector="[1,"+"0,".repeat(766)+"0]";
        zero.execute("TRUNCATE item_embedding");
        try {
            zero.update("INSERT INTO item_embedding VALUES(10,?,'model-v1')",vector);
            http.perform(put("/api/advertiser/ad/102").contentType("application/json").content("{\"title\":\"known-vector\"}")).andExpect(status().isOk());
            var known=lastEvent(102);assertThat(known.embedding().state()).isEqualTo(VersionedAdCatalogEvent.EmbeddingState.PRESENT);
            byte[] original=one.queryForObject("SELECT payload_bytes FROM catalog_outbox WHERE event_id=?",byte[].class,known.eventId());
            for(String invalid:List.of("[]","[NaN]","["+"0,".repeat(767)+"0]")) {
                zero.update("UPDATE item_embedding SET embedding=? WHERE item_id=10",invalid);
                http.perform(put("/api/advertiser/ad/102").contentType("application/json").content("{\"title\":\"invalid-vector\"}")).andExpect(status().isOk());
                var unknown=lastEvent(102);assertThat(unknown.embedding().state()).isEqualTo(VersionedAdCatalogEvent.EmbeddingState.UNKNOWN);
                assertThat(unknown.embedding().reason()).isEqualTo(VersionedAdCatalogEvent.EmbeddingReason.INVALID);
                assertThat(unknown.lifecycle()).isEqualTo(VersionedAdCatalogEvent.Lifecycle.PRESENT);
            }
            zero.execute("TRUNCATE item_embedding");
            http.perform(put("/api/advertiser/ad/102").contentType("application/json").content("{\"title\":\"empty-vector\"}")).andExpect(status().isOk());
            assertThat(lastEvent(102).embedding().reason()).isEqualTo(VersionedAdCatalogEvent.EmbeddingReason.MISSING);
            assertThat(one.queryForObject("SELECT payload_bytes FROM catalog_outbox WHERE event_id=?",byte[].class,known.eventId())).isEqualTo(original);
        } finally {zero.execute("TRUNCATE item_embedding");}
    }

    @Test void knownInitialWordLimitRejectsBeforeGuardAndNextValidCommandStillWorks() throws Exception {
        var words=java.util.Collections.nCopies(1025,new BidwordUpsert("word", "EXACT",1.0,"CPC"));
        var input=new AdUpsert(10L,"limit",null,1.0,"active","CPC",null,null,words);UUID id=UUID.randomUUID();
        http.perform(post("/api/advertiser/1/ad").contentType("application/json").content(object.writeValueAsBytes(input))
                .header(CatalogWriteRouter.COMMAND_HEADER,id).header(CatalogWriteRouter.OWNER_HEADER,"1")).andExpect(status().isConflict());
        assertThat(commands.find(CatalogProtocol.CommandKey.advertiser(SOURCE,1,id))).isNull();
        assertThat(one.queryForObject("SELECT active_command_id FROM catalog_advertiser_guard WHERE advertiser_id=1",UUID.class)).isNull();
        http.perform(put("/api/advertiser/ad/102").contentType("application/json").content("{\"title\":\"valid-after-limit\"}")).andExpect(status().isOk());
    }
    @Test void appliedPrepareReceiptSurvivesUnknownCheckpointWithoutRereadingChangedItem() throws Exception {
        UUID id=UUID.randomUUID();String body="{\"itemId\":10,\"title\":\"original-owner-receipt\"}";
        one.execute("CREATE FUNCTION catalog_test_checkpoint_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'catalog_test_checkpoint'; END $$");
        one.execute("CREATE TRIGGER catalog_test_checkpoint BEFORE UPDATE ON catalog_command FOR EACH ROW WHEN (NEW.phase='CHILDREN') EXECUTE FUNCTION catalog_test_checkpoint_fail()");
        try {
            http.perform(put("/api/advertiser/ad/102").contentType("application/json").content(body).header(CatalogWriteRouter.COMMAND_HEADER,id).header(CatalogWriteRouter.OWNER_HEADER,"1")).andExpect(status().isServiceUnavailable());
            assertThat(local.step(1,CatalogProtocol.CommandKey.advertiser(SOURCE,1,id).stepKey("PREPARE")).applied()).isTrue();
        } finally {one.execute("DROP TRIGGER catalog_test_checkpoint ON catalog_command");one.execute("DROP FUNCTION catalog_test_checkpoint_fail()");}
        zero.update("DELETE FROM item WHERE item_id=10");
        try {
            Thread.sleep(1300);
            http.perform(put("/api/advertiser/ad/102").contentType("application/json").content(body).header(CatalogWriteRouter.COMMAND_HEADER,id).header(CatalogWriteRouter.OWNER_HEADER,"1")).andExpect(status().isOk());
            assertThat(business.ad(1,102).title()).isEqualTo("original-owner-receipt");
        } finally {zero.update("INSERT INTO item VALUES(10)");}
    }

}
