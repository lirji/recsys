package com.recsys.recengine.exposure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.dto.*;
import com.recsys.common.exposure.*;
import com.recsys.content.Item;
import com.recsys.recengine.RecommendOrchestrator;
import com.recsys.recengine.content.ContentGateway;
import com.recsys.recengine.delivery.DeliveryOperationRepository;
import com.recsys.recengine.exposure.NaturalDelivery.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mybatis.spring.SqlSessionTemplate;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真实 engine PG 验证交付原子冻结、重建恢复与 fencing，测试政策不是业务默认值。 */
@Tag("natural-exposure")
@Testcontainers
class NaturalDeliveryPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static final NaturalQualification.Policy POLICY=new NaturalQualification.Policy("test-only",6000,1200,120000);
    static final NaturalQualificationToken TOKENS=new NaturalQualificationToken("test",Map.of("test",Base64.getEncoder().encodeToString(new byte[32])));
    static JdbcTemplate jdbc;
    static SqlSessionTemplate sessions;
    static NaturalDeliveryRepository repository;
    RecommendOrchestrator recommender;
    ContentGateway content;
    @BeforeAll static void migrate() throws Exception {
        var ds=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());jdbc=new JdbcTemplate(ds);
        sessions=new SqlSessionTemplate(SpringSqlSessions.create(ds,"classpath*:mapper/recengine/primary/**/*.xml"));
        for(var path:List.of(DeliveryOperationRepository.MIGRATION,NaturalDeliveryRepository.MIGRATION)) {
            String sha=path.equals(DeliveryOperationRepository.MIGRATION)?DeliveryOperationRepository.migrationSha256():NaturalDeliveryRepository.migrationSha256();
            try(var c=ds.getConnection();var stmt=c.createStatement();var in=new ClassPathResource(path).getInputStream()) {
                stmt.execute("SET recsys.migration_sha256 = '"+sha+"'");stmt.execute(new String(in.readAllBytes(),StandardCharsets.UTF_8));
            }
        }
        repository=new NaturalDeliveryRepository(sessions,JSON);repository.verifySchema();
    }
    @BeforeEach void setup() {
        recommender=mock(RecommendOrchestrator.class);content=mock(ContentGateway.class);
        candidates(1,1,2,3);
        when(content.findByIds(anyList())).thenAnswer(call->{
            var result=new HashMap<Long,Item>();
            for(long id:(List<Long>)call.getArgument(0)) result.put(id,new Item(id,"内容 "+id,"测试",List.of(),"原描述",0));
            return result;
        });
    }
    void candidates(long...ids) {
        var items=Arrays.stream(ids).mapToObj(id->new RecommendItem(id,.5,List.of("HOT"),"解释","old-cache-id")).toList();
        when(recommender.naturalCandidates(any())).thenReturn(new RecommendOrchestrator.NaturalCandidates(new RecommendResponse(1,"search",items,"trace"),"rank:first"));
    }
    Request request() { return new Request(UUID.randomUUID(),"admin",Long.MAX_VALUE,NaturalQualification.Kind.SEARCH,2,"search","查询"); }
    NaturalDeliveryService service(NaturalDeliveryRepository repo) { return new NaturalDeliveryService(repo,recommender,content,TOKENS,POLICY,true); }
    @Test void approvedNaturalTextPolicyIsFrozenWithFiveMinuteQualification() {
        var acceptedService=new NaturalDeliveryService(repository,recommender,content,TOKENS,NaturalQualification.TEXT_POLICY_V1,true);
        var r=request();var result=acceptedService.deliver(r);
        assertEquals(NaturalQualification.TEXT_POLICY_V1,result.policy());
        for(var entry:result.items()) {
            var q=TOKENS.verify(entry.qualificationToken());assertEquals(NaturalQualification.TEXT_POLICY_V1,q.policy());
            assertEquals(300000,java.time.Duration.between(q.issuedAt(),q.expiresAt()).toMillis());
        }
        assertEquals(result,acceptedService.find(r.operationId(),r.subject()).orElseThrow());
    }

    @Test void freezesDeduplicatedSlotsContentBucketAndSignedContextBeforeReturn() {
        var request=request();var result=service(repository).deliver(request);
        assertEquals(State.COMPLETE,result.state());assertEquals(List.of("1","2"),result.items().stream().map(Entry::itemId).toList());
        assertEquals(List.of(1,2),result.items().stream().map(Entry::slot).toList());
        assertEquals(Long.toString(Long.MAX_VALUE),result.userId());assertEquals(result,repository.find(request.operationId(),"admin").orElseThrow().result());
        for(var entry:result.items()) {
            var q=TOKENS.verify(entry.qualificationToken());assertEquals(request.operationId(),q.operationId());assertEquals(request.userId(),q.userId());
            assertEquals(request.subject(),q.subject());assertEquals(entry.slot(),q.slot());assertEquals(entry.itemId(),Long.toString(q.itemId()));
            assertEquals("rank:first",q.bucket());assertEquals(new Content(entry.title(),entry.category(),entry.description()).hash(),q.contentHash());
            assertEquals(POLICY,q.policy());assertEquals(entry.issuedAt().plusMillis(POLICY.ttlMs()),entry.expiresAt());
        }
        assertEquals(ModelLineage.UNVERIFIED,result.modelLineage());verify(recommender,never()).recommend(any());
    }
    @Test void committedButLostResponseRestoresExactSnapshotAcrossCandidateContentAndPolicyChanges() {
        var request=request();
        var lost=new NaturalDeliveryRepository(sessions,JSON) {
            @Override public boolean freeze(Operation o,View r) { assertTrue(super.freeze(o,r));throw new IllegalStateException("reply lost after commit"); }
        };
        assertThrows(IllegalStateException.class,()->service(lost).deliver(request));
        var frozen=repository.find(request.operationId(),"admin").orElseThrow().result();
        candidates(99);when(content.findByIds(anyList())).thenThrow(new IllegalStateException("changed source"));
        var rebuilt=new NaturalDeliveryService(new NaturalDeliveryRepository(sessions,JSON),recommender,content,TOKENS,new NaturalQualification.Policy("later-test",1,1,1000),false);
        assertEquals(frozen,rebuilt.deliver(request));assertEquals(frozen,rebuilt.find(request.operationId(),"admin").orElseThrow());
        verify(recommender,times(1)).naturalCandidates(any());verify(content,times(1)).findByIds(anyList());
    }
    @Test void failedFreezeOrContentLookupCannotDeliverUncommittedQualifications() {
        var request=request();
        var fail=new NaturalDeliveryRepository(sessions,JSON) { @Override public boolean freeze(Operation o,View v) { throw new IllegalStateException("before commit"); } };
        assertThrows(IllegalStateException.class,()->service(fail).deliver(request));
        assertEquals(State.PREPARING,service(repository).find(request.operationId(),"admin").orElseThrow().state());
        assertTrue(service(repository).find(request.operationId(),"admin").orElseThrow().items().isEmpty());
        assertEquals(State.COMPLETE,service(repository).deliver(request).state());
        var missing=request();when(content.findByIds(anyList())).thenReturn(Map.of());
        assertThrows(IllegalStateException.class,()->service(repository).deliver(missing));
        assertEquals(State.PREPARING,service(repository).find(missing.operationId(),"admin").orElseThrow().state());
    }
    /** 独立短事务必须提交检查点；调用方随后回滚不能抹掉已确认的冻结结果。 */
    @Test void outerRollbackDoesNotUndoIndependentLeaseAndFreeze() {
        var r=request();var result=empty(r);
        var outer=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        outer.setTimeout(5);
        outer.executeWithoutResult(status->{
            repository.create(r,POLICY);
            var owned=repository.acquire(r.operationId(),r.subject()).orElseThrow();
            assertEquals(1,owned.fence());
            assertTrue(repository.acquire(r.operationId(),r.subject()).isEmpty());
            assertTrue(repository.freeze(owned,result));
            status.setRollbackOnly();
        });
        var stored=repository.find(r.operationId(),r.subject()).orElseThrow();
        assertEquals(State.COMPLETE,stored.state());assertEquals(result,stored.result());
        assertTrue(repository.acquire(r.operationId(),r.subject()).isEmpty());
    }
    @Test void staleLeaseCannotFreezeOrReleaseNewOwner() {
        var r=request();repository.create(r,POLICY);var stale=repository.acquire(r.operationId(),r.subject()).orElseThrow();
        jdbc.update("UPDATE engine_natural_delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE operation_id=?",r.operationId());
        var next=repository.acquire(r.operationId(),r.subject()).orElseThrow();
        var result=empty(r);
        assertFalse(repository.freeze(stale,result));repository.relinquish(r.operationId(),stale.fence());
        assertTrue(repository.acquire(r.operationId(),r.subject()).isEmpty());assertTrue(repository.freeze(next,result));
        assertFalse(repository.freeze(stale,result));assertEquals(result,service(repository).find(r.operationId(),r.subject()).orElseThrow());
    }
    View empty(Request r) { return new View(r.operationId().toString(),NaturalQualification.Traffic.SANDBOX,State.COMPLETE,Long.toString(r.userId()),r.kind(),r.size(),r.scene(),r.query(),POLICY,"","trace",ModelLineage.UNVERIFIED,List.of()); }
    @Test void concurrentAcquisitionHasOneWinnerAndRequestCannotChange() throws Exception {
        var r=request();repository.create(r,POLICY);
        try(var pool=Executors.newFixedThreadPool(8)) {
            var tasks=new ArrayList<Future<Boolean>>();for(int i=0;i<8;i++) tasks.add(pool.submit(()->repository.acquire(r.operationId(),r.subject()).isPresent()));
            int winners=0;for(var f:tasks) if(f.get(5,TimeUnit.SECONDS)) winners++;assertEquals(1,winners);
        }
        assertThrows(NaturalReceipt.Conflict.class,()->service(repository).deliver(new Request(r.operationId(),r.subject(),r.userId(),r.kind(),r.size(),r.scene(),"changed")));
        assertThrows(NaturalReceipt.AccessDenied.class,()->repository.find(r.operationId(),"other"));
        assertEquals(POLICY,repository.create(r,new NaturalQualification.Policy("later",1,1,1)).policy());
    }
    @Test void genuineEmptyCompletesButDependencyFailureRemainsRecoverable() {
        candidates();var r=request();assertTrue(service(repository).deliver(r).items().isEmpty());assertEquals(State.COMPLETE,repository.find(r.operationId(),r.subject()).orElseThrow().state());
        when(recommender.naturalCandidates(any())).thenThrow(new IllegalStateException("down"));var failed=request();
        assertThrows(IllegalStateException.class,()->service(repository).deliver(failed));assertEquals(State.PREPARING,repository.find(failed.operationId(),failed.subject()).orElseThrow().state());
    }
    @Test void frozenHistoryCannotMutateAndEveryColumnIsDocumented() {
        var r=request();service(repository).deliver(r);
        assertThrows(RuntimeException.class,()->jdbc.update("UPDATE engine_natural_delivery SET state='PREPARING',result_json=NULL WHERE operation_id=?",r.operationId()));
        assertThrows(RuntimeException.class,()->jdbc.update("DELETE FROM engine_natural_delivery WHERE operation_id=?",r.operationId()));
        assertNotNull(jdbc.queryForObject("SELECT obj_description('engine_natural_delivery'::regclass)",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM pg_attribute WHERE attrelid='engine_natural_delivery'::regclass AND attnum>0 AND NOT attisdropped AND col_description(attrelid,attnum) IS NULL",Integer.class));
    }
    @Test void noQualificationIsReusedBetweenIndependentOperations() {
        var a=service(repository).deliver(request());var b=service(repository).deliver(request());
        assertEquals(a.items().getFirst().contentHash(),b.items().getFirst().contentHash());
        assertNotEquals(a.items().getFirst().qualificationId(),b.items().getFirst().qualificationId());
        assertNotEquals(a.items().getFirst().qualificationToken(),b.items().getFirst().qualificationToken());
    }
}
