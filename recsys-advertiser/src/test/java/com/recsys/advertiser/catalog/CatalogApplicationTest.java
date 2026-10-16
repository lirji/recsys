package com.recsys.advertiser.catalog;

import com.recsys.platform.security.InternalToken;
import com.recsys.common.ad.VersionedAdCatalogEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.recsys.advertiser.catalog.CatalogPhysicalTransactionTest.*;
import static org.assertj.core.api.Assertions.*;

/** 独立产品JVM的真实HTTP/签名认证/默认关闭装配；细粒度真实权限平台另由专用用例验收。 */
@Testcontainers @Tag("catalog-reliability") @Timeout(180)
class CatalogApplicationTest {
    @Container static final PostgreSQLContainer<?> PG0=ZERO,PG1=ONE;
    @Container static final KafkaFixture BROKER=KAFKA;
    @Container static final GenericContainer<?> REDIS=CatalogInitializationTest.REDIS;
    @TempDir(cleanup=org.junit.jupiter.api.io.CleanupMode.NEVER) Path directory;
    CatalogInitializationTest fixture;
    final List<Process> children=new ArrayList<>();
    final String secret=UUID.randomUUID().toString();
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    @BeforeAll static void startFixture() throws Exception {
        CatalogInitializationTest.startFixture();
        Files.writeString(Path.of("target/catalog-test-classpath.txt"),System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")));
    }
    @BeforeEach void prepare() throws Exception {
        fixture=new CatalogInitializationTest();fixture.prepare();
        var input=new CatalogInitializationService.Input(SOURCE,CatalogProtocol.TOPOLOGY,VersionedAdCatalogEvent.TOPIC,List.of(0,1),Map.of("init-test",7,"http-a",8,"http-b",9));
        fixture.service.initialize(fixture.id,input,"maintenance-owner","独立产品进程基线");
        for(int i=0;i<50;i++)if(fixture.service.advance(fixture.id).completed())break;fixture.binding.verify(true);
    }
    @AfterEach void stopChildren() throws Exception {
        for(var child:children)if(child.isAlive()){child.destroy();if(!child.waitFor(10,TimeUnit.SECONDS)){child.destroyForcibly();assertThat(child.waitFor(10,TimeUnit.SECONDS)).isTrue();}}
        fixture.closeReader();
    }
    @AfterAll static void closeFixture() throws Exception {CatalogInitializationTest.closeFixture();}
    record Running(Process process,int port,Path log,com.fasterxml.jackson.databind.JsonNode state) { }

    Running start(String name,boolean worker,boolean relay,boolean admission,boolean recovery) throws Exception {
        return start(name,worker,relay,admission,recovery,Map.of());
    }
    Running start(String name,boolean worker,boolean relay,boolean admission,boolean recovery,Map<String,String> overrides) throws Exception {
        // 同一用例可重启同一登记实例，保留每个真实进程的日志和启动证据。
        String artifact=name+"-"+children.size();
        Path ready=directory.resolve(artifact+".json"),log=directory.resolve(artifact+".log");
        var args=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx512m","-cp",
                System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),CatalogApplicationProcess.class.getName(),
                "--server.port=0","--spring.config.import=","--spring.cloud.nacos.config.enabled=false","--spring.cloud.nacos.discovery.enabled=false",
                "--spring.cloud.discovery.enabled=false","--spring.main.banner-mode=off"));
        var builder=new ProcessBuilder(args);var env=builder.environment();
        env.put("PG_JDBC_URL",PG0.getJdbcUrl());env.put("PG_DS1_JDBC_URL",PG1.getJdbcUrl());env.put("PG_USER",PG0.getUsername());env.put("PG_PASSWORD",PG0.getPassword());
        env.put("PG_HOST",PG0.getHost());env.put("PG_PORT",PG0.getMappedPort(5432).toString());env.put("PG_DB",PG0.getDatabaseName());
        env.put("REDIS_HOST",REDIS.getHost());env.put("REDIS_PORT",REDIS.getMappedPort(6379).toString());env.put("KAFKA_SERVERS",KAFKA.bootstrap());
        env.put("NACOS_DISCOVERY","false");env.put("RECSYS_AUTHZ_MODE","disabled");env.put("RECSYS_SECURITY_ENABLED","true");env.put("RECSYS_INTERNAL_SECRET",secret);
        env.put("ADVERTISER_SHARDING_RESOURCE","sharding-catalog-reliable.yaml");env.put("AD_CATALOG_RELIABLE","true");env.put("AD_CATALOG_ADMISSION",Boolean.toString(admission));
        env.put("AD_CATALOG_WORKER",Boolean.toString(worker));env.put("AD_CATALOG_RELAY",Boolean.toString(relay));env.put("AD_CATALOG_RECOVERY",Boolean.toString(recovery));
        env.put("AD_CATALOG_SOURCE_ID",SOURCE.toString());env.put("AD_CATALOG_WRITER_NAME",name);env.put("CATALOG_WRITER_ID",name.equals("http-a")?"8":"9");env.put("AD_CATALOG_PUBLISH","false");
        env.put("CATALOG_FIXTURE_READY",ready.toString());
        env.putAll(overrides);
        var process=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();children.add(process);
        long deadline=System.nanoTime()+Duration.ofSeconds(75).toNanos();
        while(!Files.exists(ready) && process.isAlive() && System.nanoTime()<deadline)Thread.sleep(100);
        assertThat(Files.exists(ready)).as("真实产品进程启动，日志 %s",log).isTrue();
        var state=fixture.object.readTree(Files.readString(ready));return new Running(process,state.get("port").intValue(),log,state);
    }
    HttpResponse<String> call(Running app,String method,String path,String body,String actor,String role,UUID command) throws Exception {
        return call(app,method,path,body,actor,role,command,1);
    }
    HttpResponse<String> call(Running app,String method,String path,String body,String actor,String role,UUID command,long owner) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.port()+path)).timeout(Duration.ofSeconds(12)).header("Content-Type","application/json");
        if(actor!=null)builder.header("X-Internal-Auth",InternalToken.mint(actor,role,"catalog-test",120,Instant.now().getEpochSecond(),secret));
        if(command!=null){builder.header(CatalogWriteRouter.COMMAND_HEADER,command.toString());if(!path.equals("/api/advertiser"))builder.header(CatalogWriteRouter.OWNER_HEADER,Long.toString(owner));}
        return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void realProductHttpAuthenticationQueryOriginalResponseAndSqlLogRedaction() throws Exception {
        var app=start("http-a",false,false,true,true);
        assertThat(app.state().get("routers").intValue()).isOne();assertThat(app.state().get("workers").intValue()).isZero();assertThat(app.state().get("publishers").intValue()).isZero();
        String path="/api/advertiser/ad/102";UUID command=UUID.randomUUID();String body="{\"title\":\"catalog-sensitive-title-9a53\"}";
        assertThat(call(app,"PUT",path,body,null,null,command).statusCode()).isEqualTo(401);
        assertThat(call(app,"PUT",path,body,"viewer","USER",command).statusCode()).isEqualTo(403);
        var response=call(app,"PUT",path,body,"owner","ADVERTISER",command);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue(CatalogWriteRouter.COMMAND_HEADER)).hasValue(command.toString());
        assertThat(call(app,"PUT",path,body,"owner","ADVERTISER",command).body()).isEqualTo(response.body());
        var query=call(app,"GET","/api/advertiser/1/catalog-commands/"+command,null,"owner","ADVERTISER",null);
        assertThat(query.statusCode()).isEqualTo(200);var state=fixture.object.readTree(query.body());assertThat(state.get("state").asText()).isEqualTo("COMPLETED");
        assertThat(state.at("/summary/steps/items").size()).isEqualTo(3);assertThat(state.at("/summary/events/items").size()).isOne();
        assertThat(query.body()).doesNotContain("catalog-sensitive-title-9a53");
        assertThat(call(app,"GET","/api/advertiser/1/catalog-commands/"+command+"?limit=101",null,"owner","ADVERTISER",null).statusCode()).isEqualTo(400);
        assertThat(call(app,"GET","/api/advertiser/0/catalog-commands/"+command,null,"owner","ADVERTISER",null).statusCode()).isEqualTo(400);
        assertThat(call(app,"GET","/api/advertiser/1/catalog-commands/"+UUID.randomUUID(),null,"owner","ADVERTISER",null).statusCode()).isEqualTo(404);
        assertThat(call(app,"PUT",path,"{\"title\":\"same-id-different-input\"}","owner","ADVERTISER",command).statusCode()).isEqualTo(409);
        app.process().destroy();assertThat(app.process().waitFor(10,TimeUnit.SECONDS)).isTrue();
        assertThat(Files.readString(app.log())).doesNotContain("catalog-sensitive-title-9a53","真实词");
    }
    @Test void killedIndependentJvmResumesOriginalCrossShardStepWithRealLeaseExpiration() throws Exception {
        var app=start("http-a",false,false,true,true);UUID id=UUID.randomUUID();String body="{\"title\":\"after-process-exit\"}";
        var pending=Executors.newSingleThreadExecutor();
        try(var blocker=ds0.getConnection()) {
            blocker.setAutoCommit(false);try(var statement=blocker.createStatement()){statement.execute("LOCK TABLE bidword IN ACCESS EXCLUSIVE MODE");}
            var response=pending.submit(()->call(app,"PUT","/api/advertiser/ad/102",body,"owner","ADVERTISER",id));
            await(()->zero.queryForObject("SELECT count(*) FROM catalog_step WHERE command_id=? AND step_id='CHILDREN' AND state='RUNNING'",Integer.class,id)==1,8);
            assertThat(one.queryForObject("SELECT count(*) FROM catalog_step WHERE command_id=? AND step_id='PREPARE' AND state='APPLIED'",Integer.class,id)).isOne();
            app.process().destroyForcibly();assertThat(app.process().waitFor(10,TimeUnit.SECONDS)).isTrue();blocker.rollback();
            try{assertThat(response.get(12,TimeUnit.SECONDS).statusCode()).isEqualTo(503);}catch(ExecutionException lost){assertThat(lost.getCause()).isInstanceOf(java.io.IOException.class);}
        } finally {pending.shutdownNow();}
        var next=start("http-b",true,true,true,true);
        // 等真实30秒数据库租约，不修改时间列；新的writer只能续办同一原命令。
        await(()->"COMPLETED".equals(one.queryForObject("SELECT state FROM catalog_command WHERE command_id=?",String.class,id)),45);
        var response=call(next,"PUT","/api/advertiser/ad/102",body,"owner","ADVERTISER",id);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(one.queryForObject("SELECT reserved_version FROM catalog_aggregate WHERE ad_id=102",Long.class)).isEqualTo(2);
        assertThat(zero.queryForObject("SELECT count(*) FROM catalog_step WHERE command_id=? AND step_id='CHILDREN'",Integer.class,id)).isOne();
        await(()->one.queryForObject("SELECT count(*) FROM catalog_outbox WHERE purpose='KAFKA' AND command_id=? AND state='PUBLISHED'",Integer.class,id)==1,15);
        assertThat(next.state().get("workers").intValue()).isOne();assertThat(next.state().get("publishers").intValue()).isOne();
    }
    @Test void realHttpResponseLossAfterCommitReturnsOriginalOnSameStableRetry() throws Exception {
        var app=start("http-a",false,false,true,true);UUID id=UUID.randomUUID();String body="{\"title\":\"response-loss-original\"}";
        var committed=new CountDownLatch(1);var release=new CountDownLatch(1);
        var proxy=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),1);
        var executor=Executors.newSingleThreadExecutor();proxy.setExecutor(executor);
        proxy.createContext("/lost",exchange->{
            try {var result=call(app,"PUT","/api/advertiser/ad/102",body,"owner","ADVERTISER",id);
                if(result.statusCode()==200)committed.countDown();release.await(15,TimeUnit.SECONDS);
            }catch(Exception failure){Thread.currentThread().interrupt();}
            finally{exchange.close();}
        });proxy.start();
        try {
            var lost=http.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+proxy.getAddress().getPort()+"/lost"))
                    .timeout(Duration.ofSeconds(12)).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(committed.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(one.queryForObject("SELECT state FROM catalog_command WHERE command_id=?",String.class,id)).isEqualTo("COMPLETED");
            release.countDown();assertThatThrownBy(()->lost.get(15,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            var retry=call(app,"PUT","/api/advertiser/ad/102",body,"owner","ADVERTISER",id);assertThat(retry.statusCode()).isEqualTo(200);
            assertThat(retry.body()).contains("response-loss-original");
            assertThat(one.queryForObject("SELECT reserved_version FROM catalog_aggregate WHERE ad_id=102",Long.class)).isEqualTo(2);
            assertThat(one.queryForObject("SELECT count(*) FROM catalog_outbox WHERE purpose='KAFKA' AND command_id=?",Integer.class,id)).isOne();
        } finally {release.countDown();proxy.stop(0);executor.shutdownNow();}
    }
    @Test void defaultOffBootWithActualOldProfileAndNoNewMigrationPreservesLegacyRootWrites() throws Exception {
        String database="catalog_legacy";var sources=new ArrayList<org.springframework.jdbc.datasource.DriverManagerDataSource>();
        for(var container:List.of(PG0,PG1)) {
            var admin=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(container.getJdbcUrl(),container.getUsername(),container.getPassword()));
            admin.execute("CREATE DATABASE catalog_legacy");
            var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:postgresql://"+container.getHost()+":"+container.getMappedPort(5432)+"/"+database,container.getUsername(),container.getPassword());sources.add(ds);
            var db=new org.springframework.jdbc.core.JdbcTemplate(ds);
            create(db,"advertiser","advertiser_id bigint PRIMARY KEY,name text,daily_budget double precision,status text");
            create(db,"ad","ad_id bigint PRIMARY KEY,advertiser_id bigint,item_id bigint,title text,landing_url text,quality_score double precision,status text,review_status text,optimization_type text,target_cpa double precision,audience_id bigint,review_reason text");
            create(db,"bidword","id bigint PRIMARY KEY,ad_id bigint,keyword text,match_type text,bid double precision,bid_mode text");
            create(db,"ad_creative","creative_id bigint PRIMARY KEY,ad_id bigint,title text,landing_url text,status text,review_status text DEFAULT 'approved'");
            assertThat(db.queryForObject("SELECT to_regclass('public.catalog_shard_control')::text",String.class)).isNull();
        }
        var app=start("http-a",false,false,false,false,Map.of("PG_JDBC_URL",sources.get(0).getUrl(),"PG_DS1_JDBC_URL",sources.get(1).getUrl(),"PG_DB",database,
                "AD_CATALOG_RELIABLE","false","ADVERTISER_SHARDING_RESOURCE","sharding.yaml"));
        for(String bean:List.of("routers","workers","publishers","initializers"))assertThat(app.state().get(bean).intValue()).isZero();
        var response=call(app,"POST","/api/advertiser","{\"name\":\"旧模式\",\"dailyBudget\":15}","owner","ADVERTISER",null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        long owner=fixture.object.readTree(response.body()).get("advertiserId").longValue();
        assertThat(new org.springframework.jdbc.core.JdbcTemplate(sources.get((int)(owner%2))).queryForObject("SELECT name FROM advertiser WHERE advertiser_id=?",String.class,owner)).isEqualTo("旧模式");
        for(var ds:sources)assertThat(new org.springframework.jdbc.core.JdbcTemplate(ds).queryForObject("SELECT count(*) FROM pg_class WHERE relname LIKE 'catalog_%' AND relkind='r'",Integer.class)).isZero();
        assertThatThrownBy(()->start("http-b",false,false,true,true,Map.of("PG_JDBC_URL",sources.get(0).getUrl(),"PG_DS1_JDBC_URL",sources.get(1).getUrl(),"PG_DB",database)))
                .isInstanceOf(AssertionError.class);
        var failed=children.getLast();assertThat(failed.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(failed.exitValue()).isNotZero();
        assertThat(Files.readString(directory.resolve("http-b-"+(children.size()-1)+".log"))).containsAnyOf("catalog_shard_control","advertiser_catalog_schema_history");
        for(var ds:sources)assertThat(new org.springframework.jdbc.core.JdbcTemplate(ds).queryForObject("SELECT to_regclass('public.catalog_shard_control')::text",String.class)).isNull();
    }
    @Test void recoveryOnlyRuntimeStartsBeforeSourceRegistrationAndDoesNotAdmitOrdinaryWrites() throws Exception {
        for(var db:List.of(zero,one))db.update("UPDATE catalog_shard_control SET mode='LEGACY',source_id=NULL,topology_version=NULL,initializing_command_id=NULL,initialization_ready=false,writer_manifest='{}',cluster_id=NULL,topic_id=NULL,topic_name=NULL");
        var app=start("http-a",false,false,false,true);assertThat(app.state().get("initializers").intValue()).isOne();
        assertThat(app.state().get("workers").intValue()).isZero();assertThat(app.state().get("publishers").intValue()).isZero();
        assertThat(call(app,"PUT","/api/advertiser/ad/102","{\"title\":\"不能准入\"}","owner","ADVERTISER",UUID.randomUUID()).statusCode()).isEqualTo(503);
        assertThat(fixture.business.ad(1,102).title()).isEqualTo("before");
    }
    @Test void actualPostgresStopFailsClosedAndSameRequestCompletesAfterSameContainerRestarts() throws Exception {
        var app=start("http-a",false,false,true,true);UUID id=UUID.randomUUID();String body="{\"title\":\"after-real-pg-stop\"}";
        int before=PG1.getMappedPort(5432);var docker=PG1.getDockerClient();String container=PG1.getContainerId();
        docker.stopContainerCmd(container).withTimeout(1).exec();
        try {
            long started=System.nanoTime();var unavailable=call(app,"PUT","/api/advertiser/ad/102",body,"owner","ADVERTISER",id);
            assertThat(unavailable.statusCode()).as(unavailable.body()).isEqualTo(503);
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(8));
        } finally {docker.startContainerCmd(container).exec();}
        // 实际记录端点，不把重新分配端口伪装成同一连接自动恢复。
        int after=Integer.parseInt(docker.inspectContainerCmd(container).exec().getNetworkSettings().getPorts().getBindings().get(com.github.dockerjava.api.model.ExposedPort.tcp(5432))[0].getHostPortSpec());
        assertThat(after).isEqualTo(before);
        await(()->{try{return one.queryForObject("SELECT 1",Integer.class)==1;}catch(RuntimeException pending){return false;}},30);
        // 独立探针恢复不等于产品连接池已完成重连；每次未知先查原命令，再有限续办同一身份。
        HttpResponse<String> retry=null;var observations=new ArrayList<String>();
        for(int attempt=0;attempt<6;attempt++) {
            var query=call(app,"GET","/api/advertiser/1/catalog-commands/"+id,null,"owner","ADVERTISER",null);
            assertThat(query.statusCode()).isIn(200,404,503);observations.add("query="+query.statusCode());
            retry=call(app,"PUT","/api/advertiser/ad/102",body,"owner","ADVERTISER",id);
            observations.add("retry="+retry.statusCode());
            if(retry.statusCode()==200)break;
            assertThat(retry.statusCode()).as(retry.body()).isEqualTo(503);Thread.sleep(500);
        }
        Files.writeString(directory.resolve("pg-restart-observations.json"),fixture.object.writeValueAsString(observations));
        assertThat(retry.statusCode()).as(retry.body()).isEqualTo(200);
        assertThat(one.queryForObject("SELECT reserved_version FROM catalog_aggregate WHERE ad_id=102",Long.class)).isEqualTo(2);
        assertThat(one.queryForObject("SELECT count(*) FROM catalog_command WHERE command_id=?",Integer.class,id)).isOne();
    }
    @Test void actualKafkaStopExhaustsDurableBudgetAndAuditedReplayUsesOriginalAfterRestart() throws Exception {
        var app=start("http-a",true,true,true,true);
        await(()->one.queryForObject("SELECT count(*) FROM catalog_outbox WHERE purpose='KAFKA' AND state='PUBLISHED'",Integer.class)==1,20);
        UUID id=UUID.randomUUID();int before=KAFKA.getMappedPort(19092);var docker=KAFKA.getDockerClient();String container=KAFKA.getContainerId();
        docker.stopContainerCmd(container).withTimeout(1).exec();
        String eventId=com.recsys.common.ad.CatalogEventCodec.eventId(SOURCE,102,2);
        try {
            var response=call(app,"PUT","/api/advertiser/ad/102","{\"title\":\"while-broker-stopped\"}","owner","ADVERTISER",id);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            await(()->"PAUSED".equals(one.queryForObject("SELECT state FROM catalog_outbox WHERE event_id=?",String.class,eventId)),75);
            assertThat(one.queryForObject("SELECT attempts FROM catalog_outbox WHERE event_id=?",Integer.class,eventId)).isEqualTo(5);
            assertThat(one.queryForObject("SELECT state FROM catalog_command WHERE command_id=?",String.class,id)).isEqualTo("COMPLETED");
        } finally {docker.startContainerCmd(container).exec();}
        int after=Integer.parseInt(docker.inspectContainerCmd(container).exec().getNetworkSettings().getPorts().getBindings().get(com.github.dockerjava.api.model.ExposedPort.tcp(19092))[0].getHostPortSpec());assertThat(after).isEqualTo(before);
        await(()->{try{return CatalogKafkaPublisher.identity(KAFKA.bootstrap()).equals(kafkaIdentity);}catch(RuntimeException pending){return false;}},35);
        var original=fixture.local.event(1,eventId);var recovery=new CatalogRecoveryService(fixture.local,CatalogInitializationTest.session.getMapper(com.recsys.advertiser.persistence.mapper.primary.CatalogRecoveryMapper.class),
                new CatalogLocalTransactions(sharded,fixture.local),fixture.binding,new CatalogJson(fixture.object),new CatalogSnapshotCodec(fixture.object),SOURCE,KAFKA.bootstrap());
        UUID replay=UUID.randomUUID();recovery.submit(replay,new CatalogRecoveryService.Request(CatalogProtocol.Kind.REPLAY_EVENT,null,null,null,0,0,1,eventId,original.payloadSha256()),"maintenance-owner","真实broker停机后原文受控续发");
        recovery.advance(replay);assertThat(recovery.advance(replay).completed()).isTrue();
        // 第五次send等待超时后底层delivery仍可迟到成功；恢复允许原字节重复，不要求虚假的exactly-once。
        verifyOriginalCopies(original,CatalogProtocol.SEND_ATTEMPTS+recovery.query(replay).attempts());
        assertThat(fixture.local.event(1,eventId).payloadBytes()).isEqualTo(original.payloadBytes());
        assertThat(one.queryForObject("SELECT reserved_version FROM catalog_aggregate WHERE ad_id=102",Long.class)).isEqualTo(2);
    }
    @Test void sourceCommittedBeforeRelayProcessExitStillPublishesExactOriginalOnRestart() throws Exception {
        var app=start("http-a",false,false,true,true);UUID id=UUID.randomUUID();
        assertThat(call(app,"PUT","/api/advertiser/ad/102","{\"title\":\"committed-before-relay\"}","owner","ADVERTISER",id).statusCode()).isEqualTo(200);
        String eventId=com.recsys.common.ad.CatalogEventCodec.eventId(SOURCE,102,2);var original=fixture.local.event(1,eventId);assertThat(original.state()).isEqualTo("READY");
        app.process().destroyForcibly();assertThat(app.process().waitFor(10,TimeUnit.SECONDS)).isTrue();
        start("http-b",false,true,false,true);
        await(()->"PUBLISHED".equals(fixture.local.event(1,eventId).state()),15);
        var record=new CatalogPhysicalTransactionTest().consume(eventId,1).getFirst();assertThat(record.key()).isEqualTo(original.keyBytes());assertThat(record.value()).isEqualTo(original.payloadBytes());
    }
    @Test void brokerAcknowledgedThenJvmKilledBeforePgMarkerRepeatsOnlyOriginalBytes() throws Exception {
        // 初始化已真实发布v1；必须先经产品入口生成尚未发送的v2，才能进入ACK后标记前窗口。
        var writer=start("http-a",false,false,true,true);
        assertThat(call(writer,"PUT","/api/advertiser/ad/102","{\"title\":\"ack-window-v2\"}","owner","ADVERTISER",UUID.randomUUID()).statusCode()).isEqualTo(200);
        writer.process().destroy();assertThat(writer.process().waitFor(10,TimeUnit.SECONDS)).isTrue();
        String eventId=com.recsys.common.ad.CatalogEventCodec.eventId(SOURCE,102,2);var original=fixture.local.event(1,eventId);
        assertThat(original.state()).isEqualTo("READY");
        // 独占PG提交断点：只拦PUBLISHED标记，真实Kafka发送和ACK均由产品完成。
        one.execute("CREATE FUNCTION catalog_test_hold_publish() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(91605); RETURN NEW; END $$");
        one.execute("CREATE TRIGGER catalog_test_publish BEFORE UPDATE ON catalog_outbox FOR EACH ROW WHEN (NEW.state='PUBLISHED') EXECUTE FUNCTION catalog_test_hold_publish()");
        try(var lock=ds1.getConnection()) {
            try(var statement=lock.createStatement()){statement.execute("SELECT pg_advisory_lock(91605)");}
            var app=start("http-a",false,true,false,true);
            var first=new CatalogPhysicalTransactionTest().consume(eventId,1);assertThat(first.getFirst().value()).isEqualTo(original.payloadBytes());
            await(()->one.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event='advisory'",Integer.class)>0,8);
            app.process().destroyForcibly();assertThat(app.process().waitFor(10,TimeUnit.SECONDS)).isTrue();
            assertThat(fixture.local.event(1,eventId).state()).isEqualTo("SENDING");
            try(var statement=lock.createStatement()){statement.execute("SELECT pg_advisory_unlock(91605)");}
        } finally {one.execute("DROP TRIGGER catalog_test_publish ON catalog_outbox");one.execute("DROP FUNCTION catalog_test_hold_publish()");}
        start("http-b",false,true,false,true);
        await(()->"PUBLISHED".equals(fixture.local.event(1,eventId).state()),45);
        var records=new CatalogPhysicalTransactionTest().consume(eventId,2);
        for(var row:records){assertThat(row.key()).isEqualTo(original.keyBytes());assertThat(row.value()).isEqualTo(original.payloadBytes());}
        assertThat(records.get(0).offset()).isLessThan(records.get(1).offset());assertThat(fixture.local.event(1,eventId).attempts()).isEqualTo(2);
    }
    @Test void twoRealWriterJvmsCannotInterleaveDifferentCommandsAndRootIdentityIsReservedOnce() throws Exception {
        var first=start("http-a",false,false,true,true);var second=start("http-b",false,false,true,true);
        UUID id=UUID.randomUUID();String body="{\"title\":\"original-serial-owner\"}";var executor=Executors.newFixedThreadPool(2);
        try(var blocker=ds0.getConnection()) {
            blocker.setAutoCommit(false);try(var statement=blocker.createStatement()){statement.execute("LOCK TABLE bidword IN ACCESS EXCLUSIVE MODE");}
            var original=executor.submit(()->call(first,"PUT","/api/advertiser/ad/102",body,"owner","ADVERTISER",id));
            await(()->zero.queryForObject("SELECT count(*) FROM catalog_step WHERE command_id=? AND step_id='CHILDREN' AND state='RUNNING'",Integer.class,id)==1,8);
            assertThat(call(second,"PUT","/api/advertiser/ad/102","{\"title\":\"other-command\"}","owner","ADVERTISER",UUID.randomUUID()).statusCode()).isEqualTo(409);
            assertThat(call(second,"PUT","/api/advertiser/ad/102",body,"owner","ADVERTISER",id).statusCode()).isEqualTo(503);
            blocker.rollback();var completed=original.get(12,TimeUnit.SECONDS);assertThat(completed.statusCode()).as(completed.body()).isEqualTo(200);
            byte[] saved=fixture.local.command(CatalogProtocol.CommandKey.advertiser(SOURCE,1,id)).resultBytes();
            var retried=call(second,"PUT","/api/advertiser/ad/102",body,"owner","ADVERTISER",id);
            assertThat(retried.statusCode()).isEqualTo(200);
            // HTTP DTO字段顺序不是协议；持久原结果字节和事件字节则必须保持不变。
            assertThat(fixture.object.readTree(retried.body())).isEqualTo(fixture.object.readTree(completed.body()));
            assertThat(fixture.local.command(CatalogProtocol.CommandKey.advertiser(SOURCE,1,id)).resultBytes()).isEqualTo(saved);
            UUID root=UUID.randomUUID();String create="{\"name\":\"同ID开户\",\"dailyBudget\":10}";
            var a=executor.submit(()->call(first,"POST","/api/advertiser",create,"owner","ADMIN",root));
            var b=executor.submit(()->call(second,"POST","/api/advertiser",create,"owner","ADMIN",root));
            assertThat(a.get(12,TimeUnit.SECONDS).statusCode()).isIn(200,503);assertThat(b.get(12,TimeUnit.SECONDS).statusCode()).isIn(200,503);
            var result=call(second,"POST","/api/advertiser",create,"owner","ADMIN",root);assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
            var home=(root.getLeastSignificantBits()&1)==0?zero:one;long owner=fixture.object.readTree(result.body()).get("advertiserId").longValue();
            assertThat(home.queryForObject("SELECT entity_id FROM catalog_identity WHERE command_id=?",Long.class,root)).isEqualTo(owner);
            assertThat(home.queryForObject("SELECT count(*) FROM catalog_identity WHERE command_id=?",Integer.class,root)).isOne();
            UUID differentActor=UUID.randomUUID();
            List<Integer> statuses;
            var rootHome=(differentActor.getLeastSignificantBits()&1)==0?zero:one;
            try(var gate=((differentActor.getLeastSignificantBits()&1)==0?ds0:ds1).getConnection()) {
                gate.setAutoCommit(false);try(var statement=gate.createStatement()){statement.execute("LOCK TABLE catalog_command IN SHARE MODE");}
                var actorA=executor.submit(()->call(first,"POST","/api/advertiser",create,"actor-a","ADMIN",differentActor));
                var actorB=executor.submit(()->call(second,"POST","/api/advertiser",create,"actor-b","ADMIN",differentActor));
                // 两次首次查询已看见空行，再同时竞争insert，确定命中原actor遗漏窗口。
                await(()->rootHome.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE 'INSERT INTO catalog_command%'",Integer.class)>=2,3);
                gate.rollback();statuses=List.of(actorA.get(12,TimeUnit.SECONDS).statusCode(),actorB.get(12,TimeUnit.SECONDS).statusCode());
            }
            assertThat(statuses).contains(409);assertThat(statuses.stream().filter(code->code==409).count()).isOne();
            var originalActor=fixture.local.command(CatalogProtocol.CommandKey.root(SOURCE,differentActor)).actor();
            assertThat(call(second,"POST","/api/advertiser",create,originalActor,"ADMIN",differentActor).statusCode()).isEqualTo(200);
            assertThat(call(first,"POST","/api/advertiser",create,originalActor.equals("actor-a")?"actor-b":"actor-a","ADMIN",differentActor).statusCode()).isEqualTo(409);
            UUID shared=UUID.randomUUID();
            var ownerOne=executor.submit(()->call(first,"PUT","/api/advertiser/ad/102","{\"title\":\"scope-one\"}","owner","ADVERTISER",shared,1));
            var ownerTwo=executor.submit(()->call(second,"PUT","/api/advertiser/ad/103","{\"title\":\"scope-two\"}","owner","ADVERTISER",shared,2));
            assertThat(ownerOne.get(12,TimeUnit.SECONDS).statusCode()).isEqualTo(200);assertThat(ownerTwo.get(12,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(one.queryForObject("SELECT scope_id FROM catalog_command WHERE command_id=?",String.class,shared)).isEqualTo("1");
            assertThat(zero.queryForObject("SELECT scope_id FROM catalog_command WHERE command_id=?",String.class,shared)).isEqualTo("2");
        } finally {executor.shutdownNow();}
    }
    @Test void realDerivedConnectionRefusalProducesUnknownForNewItemAndDeleteDoesNotDependOnDerived() throws Exception {
        int unused;try(var socket=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())){unused=socket.getLocalPort();}
        zero.update("INSERT INTO item VALUES(20)");
        var app=start("http-a",false,false,true,true,Map.of("PG_PORT",Integer.toString(unused)));
        assertThat(call(app,"PUT","/api/advertiser/ad/102","{\"title\":\"派生不可达\"}","owner","ADVERTISER",UUID.randomUUID()).statusCode()).isEqualTo(200);
        var codec=new CatalogSnapshotCodec(fixture.object);
        var original=fixture.local.event(1,com.recsys.common.ad.CatalogEventCodec.eventId(SOURCE,102,2));var unknown=codec.decode(original.keyBytes(),original.payloadBytes());
        assertThat(unknown.embedding().state()).isEqualTo(VersionedAdCatalogEvent.EmbeddingState.UNKNOWN);assertThat(unknown.embedding().reason()).isEqualTo(VersionedAdCatalogEvent.EmbeddingReason.UNAVAILABLE);
        assertThat(call(app,"PUT","/api/advertiser/ad/102","{\"itemId\":20}","owner","ADVERTISER",UUID.randomUUID()).statusCode()).isEqualTo(200);
        var changed=fixture.local.event(1,com.recsys.common.ad.CatalogEventCodec.eventId(SOURCE,102,3));var event=codec.decode(changed.keyBytes(),changed.payloadBytes());
        assertThat(event.embedding().itemId()).isEqualTo(20);assertThat(event.embedding().vector()).isNull();assertThat(event.ad().itemId()).isEqualTo(20);
        assertThat(call(app,"DELETE","/api/advertiser/ad/102",null,"owner","ADVERTISER",UUID.randomUUID()).statusCode()).isEqualTo(200);
        var removed=fixture.local.event(1,com.recsys.common.ad.CatalogEventCodec.eventId(SOURCE,102,4));var deleted=codec.decode(removed.keyBytes(),removed.payloadBytes());
        assertThat(deleted.lifecycle()).isEqualTo(VersionedAdCatalogEvent.Lifecycle.DELETED);assertThat(deleted.embedding().state()).isEqualTo(VersionedAdCatalogEvent.EmbeddingState.NOT_APPLICABLE);
        assertThat(fixture.local.event(1,original.eventId()).payloadBytes()).isEqualTo(original.payloadBytes());
    }
    private static void await(java.util.function.BooleanSupplier condition,int seconds) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();while(System.nanoTime()<deadline){if(condition.getAsBoolean())return;Thread.sleep(100);}fail("独占进程验收条件超时");
    }
    private void verifyOriginalCopies(CatalogOutboxRepository.EventRow original,int maximum) {
        var properties=Map.<String,Object>of("bootstrap.servers",KAFKA.bootstrap(),"group.id","catalog-outage-"+UUID.randomUUID(),"enable.auto.commit",false,
                "key.deserializer","org.apache.kafka.common.serialization.ByteArrayDeserializer","value.deserializer","org.apache.kafka.common.serialization.ByteArrayDeserializer");
        int copies=0;var codec=new CatalogSnapshotCodec(fixture.object);
        try(var consumer=new org.apache.kafka.clients.consumer.KafkaConsumer<byte[],byte[]>(properties)) {
            var partition=new org.apache.kafka.common.TopicPartition(VersionedAdCatalogEvent.TOPIC,0);consumer.assign(List.of(partition));consumer.seekToBeginning(List.of(partition));
            long until=System.nanoTime()+Duration.ofSeconds(10).toNanos();
            while(System.nanoTime()<until)for(var record:consumer.poll(Duration.ofMillis(200)))if(codec.decode(record.key(),record.value()).eventId().equals(original.eventId())) {
                assertThat(record.key()).isEqualTo(original.keyBytes());assertThat(record.value()).isEqualTo(original.payloadBytes());copies++;
            }
        }
        assertThat(copies).isBetween(1,maximum);
    }
    @Test void realHttpChildCrudReviewStatusAndDeletionCleanupRecoverOriginalTombstone() throws Exception {
        var app=start("http-a",false,false,true,true);
        var word=call(app,"POST","/api/advertiser/ad/102/bidword","{\"keyword\":\"http-word\",\"bid\":2}","owner","ADVERTISER",UUID.randomUUID());
        assertThat(word.statusCode()).as(word.body()).isEqualTo(200);long wordId=fixture.object.readTree(word.body()).get("id").longValue();
        assertThat(call(app,"PUT","/api/advertiser/bidword/"+wordId,"{\"bid\":3}","owner","ADVERTISER",UUID.randomUUID()).statusCode()).isEqualTo(200);
        var creative=call(app,"POST","/api/advertiser/ad/102/creative","{\"title\":\"http-creative\"}","owner","ADVERTISER",UUID.randomUUID());
        assertThat(creative.statusCode()).isEqualTo(200);long creativeId=fixture.object.readTree(creative.body()).get("creativeId").longValue();
        assertThat(call(app,"PUT","/api/advertiser/creative/"+creativeId,"{\"status\":\"paused\"}","owner","ADVERTISER",UUID.randomUUID()).statusCode()).isEqualTo(200);
        assertThat(call(app,"PUT","/api/advertiser/ad/102/status?status=paused",null,"owner","ADVERTISER",UUID.randomUUID()).statusCode()).isEqualTo(200);
        assertThat(call(app,"POST","/api/advertiser/ad/102/review?decision=reject&reason=review-test",null,"reviewer","ADMIN",UUID.randomUUID()).statusCode()).isEqualTo(200);
        assertThat(call(app,"POST","/api/advertiser/ad/102/submit-review",null,"owner","ADVERTISER",UUID.randomUUID()).statusCode()).isEqualTo(200);
        assertThat(fixture.business.ad(1,102).reviewStatus()).isEqualTo("pending_review");
        UUID deleteWord=UUID.randomUUID();for(int retry=0;retry<2;retry++)assertThat(call(app,"DELETE","/api/advertiser/bidword/"+wordId,null,"owner","ADVERTISER",deleteWord).statusCode()).isEqualTo(200);
        UUID deleteCreative=UUID.randomUUID();for(int retry=0;retry<2;retry++)assertThat(call(app,"DELETE","/api/advertiser/creative/"+creativeId,null,"owner","ADVERTISER",deleteCreative).statusCode()).isEqualTo(200);
        UUID deletion=UUID.randomUUID();
        zero.execute("CREATE FUNCTION catalog_test_cleanup_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'catalog_test_cleanup'; END $$");
        zero.execute("CREATE TRIGGER catalog_test_cleanup BEFORE DELETE ON bidword FOR EACH ROW EXECUTE FUNCTION catalog_test_cleanup_fail()");
        byte[] tombstone;long version;
        try {
            assertThat(call(app,"DELETE","/api/advertiser/ad/102",null,"owner","ADVERTISER",deletion).statusCode()).isEqualTo(503);
            assertThat(fixture.business.ad(1,102)).isNull();assertThat(fixture.business.bidwords(102,0)).hasSize(1);
            var query=call(app,"GET","/api/advertiser/1/catalog-commands/"+deletion,null,"owner","ADVERTISER",null);
            assertThat(query.statusCode()).isEqualTo(200);assertThat(fixture.object.readTree(query.body()).get("phase").asText()).isEqualTo("CHILDREN");
            tombstone=one.queryForObject("SELECT payload_bytes FROM catalog_outbox WHERE command_id=? AND purpose='KAFKA'",byte[].class,deletion);
            var event=new CatalogSnapshotCodec(fixture.object).decode(com.recsys.common.ad.CatalogEventCodec.key(102),tombstone);
            version=event.aggregateVersion();assertThat(event.lifecycle()).isEqualTo(VersionedAdCatalogEvent.Lifecycle.DELETED);
            assertThat(call(app,"PUT","/api/advertiser/ad/102","{\"title\":\"cannot-resurrect\"}","owner","ADVERTISER",UUID.randomUUID()).statusCode()).isEqualTo(404);
            assertThat(one.queryForObject("SELECT active_command_id FROM catalog_advertiser_guard WHERE advertiser_id=1",UUID.class)).isEqualTo(deletion);
        } finally {zero.execute("DROP TRIGGER catalog_test_cleanup ON bidword");zero.execute("DROP FUNCTION catalog_test_cleanup_fail()");}
        Thread.sleep(1300);
        for(int retry=0;retry<2;retry++)assertThat(call(app,"DELETE","/api/advertiser/ad/102",null,"owner","ADVERTISER",deletion).statusCode()).isEqualTo(200);
        assertThat(fixture.business.bidwords(102,0)).isEmpty();assertThat(fixture.business.ad(1,102)).isNull();
        assertThat(one.queryForObject("SELECT payload_bytes FROM catalog_outbox WHERE command_id=? AND purpose='KAFKA'",byte[].class,deletion)).isEqualTo(tombstone);
        assertThat(one.queryForObject("SELECT reserved_version FROM catalog_aggregate WHERE ad_id=102",Long.class)).isEqualTo(version);
    }

    @Test void killedInitializationJvmAfterOnePhysicalQuiesceResumesOnlyOriginalIdAndPublishesBaseline() throws Exception {
        fixture.closeReader();fixture.prepare();UUID id=fixture.id;
        var first=start("http-a",false,false,false,true,Map.of("CATALOG_FIXTURE_INITIALIZE",id.toString(),"CATALOG_FIXTURE_INITIALIZE_MODE","quiesce-first"));
        assertThat(fixture.local.binding(0).mode()).isEqualTo("QUIESCED");assertThat(fixture.local.binding(1).mode()).isEqualTo("LEGACY");
        first.process().destroyForcibly();assertThat(first.process().waitFor(10,TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(()->fixture.service.initialize(UUID.randomUUID(),fixture.input(),"other","不得重新采样")).isInstanceOf(CatalogProtocol.CatalogException.class);
        start("http-b",false,false,false,true,Map.of("CATALOG_FIXTURE_INITIALIZE",id.toString(),"CATALOG_FIXTURE_INITIALIZE_MODE","complete"));
        fixture.binding.verify(true);
        for(int shard=0;shard<2;shard++) {
            assertThat(fixture.local.binding(shard).initializingCommandId()).isEqualTo(id);
            var db=shard==0?zero:one;
            assertThat(db.queryForObject("SELECT count(*) FROM catalog_outbox WHERE purpose='KAFKA' AND state='PUBLISHED'",Integer.class)).isOne();
            assertThat(db.queryForObject("SELECT count(*) FROM catalog_task_item WHERE parent_command_id=?",Integer.class,id)).isOne();
        }
    }

}
