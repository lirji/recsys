package com.recsys.advertiser.catalog;

import com.recsys.advertiser.dto.AdUpsert;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.junit.jupiter.Container;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.recsys.advertiser.catalog.CatalogProtocol.*;
import static com.recsys.advertiser.catalog.CatalogPhysicalTransactionTest.*;
import static org.assertj.core.api.Assertions.*;

/** 末次真实领取与开户预留后的独立JVM退出，不改数据库时间列或产品预算。 */
@Testcontainers @Tag("catalog-reliability") @Timeout(180)
class CatalogLeaseExitTest {
    @Container static final org.testcontainers.containers.PostgreSQLContainer<?> PG0=ZERO,PG1=ONE;
    @Container static final KafkaFixture BROKER=KAFKA;
    @Container static final org.testcontainers.containers.GenericContainer<?> REDIS=CatalogInitializationTest.REDIS;
    @TempDir(cleanup=org.junit.jupiter.api.io.CleanupMode.NEVER) Path directory;
    CatalogApplicationTest app;
    @BeforeAll static void startFixture() throws Exception {CatalogApplicationTest.startFixture();}
    @AfterAll static void closeFixture() throws Exception {CatalogApplicationTest.closeFixture();}
    @BeforeEach void prepare() throws Exception {app=new CatalogApplicationTest();app.directory=directory;app.prepare();}
    @AfterEach void finish() throws Exception {app.stopChildren();}

    @Test void killedLastCommandStepAndSendClaimsPauseAfterRealLeaseWithoutResettingBudgets() throws Exception {
        var writer=app.start("http-a",false,false,true,true);
        assertThat(app.call(writer,"PUT","/api/advertiser/ad/102","{\"title\":\"before-last-claims\"}","owner","ADVERTISER",UUID.randomUUID()).statusCode()).isEqualTo(200);
        writer.process().destroy();assertThat(writer.process().waitFor(10,TimeUnit.SECONDS)).isTrue();
        var fixture=app.fixture;var tx=new CatalogLocalTransactions(sharded,fixture.local);
        var commands=new CatalogCommandRepository(fixture.local,tx);var steps=new CatalogStepRepository(fixture.local,tx,fixture.object);
        UUID id=UUID.randomUUID();var key=CommandKey.advertiser(SOURCE,1,id);
        var mutation=new CatalogMutation(Kind.AD_UPDATE,102,new AdUpsert(null,"unapplied",null,null,null,null,null,null,null),null,null,null,null,null,null);
        commands.admit(new Submission(key,Kind.AD_UPDATE,new CatalogJson(fixture.object).encode(new CatalogFragments.Request(mutation,102L)),"owner","末次退出"));
        var step=CatalogApplicationProcess.exitStep(commands.find(key));steps.register(step);
        for(int n=1;n<=2;n++) {
            var c=claim(()->commands.claim(key));assertThat(c.attempts()).isEqualTo(n);commands.failed(c,Failure.RESULT_UNKNOWN);
            var s=claim(()->steps.claim(step));assertThat(s.attempts()).isEqualTo(n);assertThat(steps.failed(s,Failure.RESULT_UNKNOWN)).isTrue();
        }
        String event=com.recsys.common.ad.CatalogEventCodec.eventId(SOURCE,102,2);
        for(int n=1;n<=4;n++) {
            var c=claim(()->{var rows=fixture.outbox.claim(1,SOURCE);return rows.isEmpty()?null:rows.getFirst();});
            assertThat(c.eventId()).isEqualTo(event);assertThat(c.attempts()).isEqualTo(n);assertThat(fixture.outbox.failed(c,Failure.RESULT_UNKNOWN)).isTrue();
        }
        var child=app.start("http-a",false,false,false,true,Map.of("CATALOG_FIXTURE_LAST_CLAIM",id.toString(),"CATALOG_FIXTURE_LAST_EVENT",event));
        assertThat(commands.find(key).attempts()).isEqualTo(3);assertThat(steps.find(step).attempts()).isEqualTo(3);
        assertThat(fixture.outbox.find(1,event).attempts()).isEqualTo(5);
        child.process().destroyForcibly();assertThat(child.process().waitFor(10,TimeUnit.SECONDS)).isTrue();
        app.start("http-b",true,true,false,true);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
        while(!("PAUSED".equals(commands.find(key).state()) && "PAUSED".equals(steps.find(step).state())
                && "PAUSED".equals(fixture.outbox.find(1,event).state())) && System.nanoTime()<deadline)Thread.sleep(100);
        assertThat(commands.find(key).state()).isEqualTo("PAUSED");assertThat(commands.find(key).attempts()).isEqualTo(3);
        assertThat(steps.find(step).state()).isEqualTo("PAUSED");assertThat(steps.find(step).attempts()).isEqualTo(3);
        assertThat(fixture.outbox.find(1,event).state()).isEqualTo("PAUSED");assertThat(fixture.outbox.find(1,event).attempts()).isEqualTo(5);
        assertThat(fixture.local.lockGuard(1,1).activeCommandId()).isEqualTo(id);
        assertThat(fixture.business.ad(1,102).title()).isEqualTo("before-last-claims");
        assertThatThrownBy(()->commands.admit(new Submission(CommandKey.advertiser(SOURCE,1,UUID.randomUUID()),Kind.AD_UPDATE,utf8("{}"),"owner","不得解占有"))).isInstanceOf(CatalogException.class);
        var recovery=new CatalogRecoveryService(fixture.local,CatalogInitializationTest.session.getMapper(com.recsys.advertiser.persistence.mapper.primary.CatalogRecoveryMapper.class),
                tx,fixture.binding,new CatalogJson(fixture.object),new CatalogSnapshotCodec(fixture.object),SOURCE,KAFKA.bootstrap());
        UUID audit=UUID.randomUUID();recovery.submit(audit,new CatalogRecoveryService.Request(Kind.RETRY_STEP,key,1,step.stepKey(),1,1,null,null,commands.find(key).requestSha256()),"owner","末次退出后审计原步骤续办");
        for(int n=0;n<3;n++)recovery.advance(audit);assertThat(recovery.query(audit).completed()).isTrue();
        deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(!commands.find(key).completed() && System.nanoTime()<deadline)Thread.sleep(100);
        assertThat(commands.find(key).completed()).isTrue();assertThat(commands.find(key).round()).isEqualTo(2);
        assertThat(steps.find(step).applied()).isTrue();assertThat(steps.find(step).round()).isEqualTo(2);
        assertThat(fixture.business.ad(1,102).title()).isEqualTo("unapplied");
        assertThat(fixture.local.lockGuard(1,1).activeCommandId()).isNull();
    }

    @Test void rootIdentityReservedInKilledJvmIsQueriedAndCompletedWithOriginalIdAndActor() throws Exception {
        UUID id=UUID.randomUUID();var key=CommandKey.root(SOURCE,id);
        var first=app.start("http-a",false,false,true,true,Map.of("CATALOG_FIXTURE_RESERVE_ROOT",id.toString()));
        var original=app.fixture.local.command(key);assertThat(original.actor()).isEqualTo("owner");assertThat(original.advertiserId()).isPositive();
        first.process().destroyForcibly();assertThat(first.process().waitFor(10,TimeUnit.SECONDS)).isTrue();
        var next=app.start("http-b",false,false,true,true);
        var query=app.call(next,"GET","/api/advertiser/catalog-commands/"+id,null,"owner","ADMIN",null);
        assertThat(query.statusCode()).isEqualTo(200);assertThat(app.fixture.object.readTree(query.body()).get("advertiserId").asLong()).isEqualTo(original.advertiserId());
        String body="{\"name\":\"reserved-exit\",\"dailyBudget\":90}";
        assertThat(app.call(next,"POST","/api/advertiser",body,"other","ADMIN",id).statusCode()).isEqualTo(409);
        var completed=app.call(next,"POST","/api/advertiser",body,"owner","ADMIN",id);assertThat(completed.statusCode()).as(completed.body()).isEqualTo(200);
        assertThat(app.fixture.object.readTree(completed.body()).get("advertiserId").asLong()).isEqualTo(original.advertiserId());
        var db=key.shard()==0?zero:one;
        assertThat(db.queryForObject("SELECT count(*) FROM catalog_identity WHERE command_id=?",Integer.class,id)).isOne();
        assertThat(app.fixture.local.command(key).actor()).isEqualTo("owner");
    }
    private static <T>T claim(java.util.function.Supplier<T> action) throws InterruptedException {
        for(int n=0;n<150;n++){T value=action.get();if(value!=null)return value;Thread.sleep(100);}
        throw new AssertionError("实际有限退避后仍未获准领取");
    }
}
