package com.recsys.advertiser.catalog;

import com.fasterxml.jackson.databind.*;
import org.testcontainers.containers.GenericContainer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.recsys.advertiser.catalog.CatalogPhysicalTransactionTest.*;
import static org.assertj.core.api.Assertions.*;

/** 显式执行的真实SDK→权限服务→独占SpiceDB验收，不进入默认测试时伪装成跳过/PASS。 */
public final class CatalogRealAuthzAcceptance {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final HttpClient CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static String authUrl,token;
    public static void main(String[] args) throws Exception {
        Path jar=Path.of(required("ACTIONS_AUTHZ_SERVER_JAR")),schema=Path.of(required("ACTIONS_AUTHZ_SCHEMA"));
        if(!Files.isRegularFile(jar) || !Files.isRegularFile(schema))throw new IllegalStateException("需要已核验的权限平台jar和schema");
        String scenario=System.getenv().getOrDefault("CATALOG_AUTHZ_SCENARIO","full");
        if(!Set.of("full","root-recovery","status-fanout").contains(scenario))throw new IllegalArgumentException("未知权限验收范围");
        boolean rootOnly=scenario.equals("root-recovery");
        Path evidence=Path.of("target","catalog-authz-evidence-"+UUID.randomUUID());Files.createDirectories(evidence);
        String key=UUID.randomUUID().toString();token=UUID.randomUUID().toString();
        var spice=new GenericContainer<>(required("ACTIONS_SPICEDB_IMAGE")).withExposedPorts(8443)
                .withEnv("SPICEDB_GRPC_PRESHARED_KEY",key).withCommand("serve","--http-enabled","--http-addr=:8443","--dispatch-cluster-enabled=false");
        Process server=null;com.sun.net.httpserver.HttpServer proxy=null;ExecutorService proxyWorkers=null;CatalogApplicationTest app=null;boolean fixtureStarted=false;
        try {
            spice.start();String spiceUrl="http://"+spice.getHost()+":"+spice.getMappedPort(8443);
            until(()->send(spiceUrl,"/v1/schema/write",Map.of("schema",Files.readString(schema)),key).statusCode()==200,30);
            int port;try(var socket=new java.net.ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
            var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",jar.toString(),"--server.address=127.0.0.1","--server.port="+port);
            builder.environment().putAll(Map.of("SPICEDB_HTTP",spiceUrl,"SPICEDB_KEY",key,"AUTHZ_SERVER_SECURITY_ENABLED","true","AUTHZ_SERVER_TOKEN",token));
            server=builder.redirectErrorStream(true).redirectOutput(evidence.resolve("authz-server.log").toFile()).start();authUrl="http://127.0.0.1:"+port;
            until(()->send(authUrl,"/actuator/health",null,null).statusCode()==200,40);
            relations(List.of(relation("platform","recsys","admin","user","creator","TOUCH"),relation("platform","recsys","admin","user","other-admin","TOUCH"),
                    relation("platform","recsys","operator","user","reviewer","TOUCH"),relation("advertiser","1","platform","platform","recsys","TOUCH"),
                    relation("advertiser","1","owner","user","owner","TOUCH"),relation("advertiser","1","member","user","editor","TOUCH")));
            AtomicBoolean dropRelationships=new AtomicBoolean(false),lost=new AtomicBoolean(false);
            proxy=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),10);proxyWorkers=Executors.newFixedThreadPool(2);proxy.setExecutor(proxyWorkers);
            proxy.createContext("/",exchange->{
                try {
                    var request=HttpRequest.newBuilder(URI.create(authUrl+exchange.getRequestURI())).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json")
                            .header("Authorization","Bearer "+token).method(exchange.getRequestMethod(),HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())).build();
                    var response=CLIENT.send(request,HttpResponse.BodyHandlers.ofByteArray());
                    if(exchange.getRequestURI().getPath().equals("/v1/relationships") && dropRelationships.get() && response.statusCode()==200){lost.set(true);return;}
                    exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(response.statusCode(),response.body().length);exchange.getResponseBody().write(response.body());
                }catch(Exception error){/* 此代理只制造真实响应丢失，不伪造成功或权限结果。 */}
                finally{exchange.close();}
            });proxy.start();
            ZERO.start();ONE.start();KAFKA.start();CatalogInitializationTest.REDIS.start();
            CatalogApplicationTest.startFixture();fixtureStarted=true;app=new CatalogApplicationTest();app.directory=evidence;app.prepare();
            var runtime=app.start("http-a",false,false,true,true,Map.of("RECSYS_AUTHZ_MODE","enforce","AUTHZ_SERVER_URL","http://127.0.0.1:"+proxy.getAddress().getPort(),"AUTHZ_CLIENT_TOKEN",token));
            if(scenario.equals("status-fanout")) {
                String body="{\"status\":\"paused\"}";UUID id=UUID.randomUUID();
                assertThat(app.call(runtime,"PUT","/api/advertiser/1",body,"editor","ADVERTISER",id).statusCode()).isEqualTo(403);
                assertThat(app.call(runtime,"PUT","/api/advertiser/1",body,"stranger","ADMIN",id).statusCode()).isEqualTo(403);
                assertThat(app.fixture.local.command(CatalogProtocol.CommandKey.advertiser(SOURCE,1,id))).isNull();
                assertThat(app.call(runtime,"PUT","/api/advertiser/1",body,"owner","ADVERTISER",id).statusCode()).isEqualTo(503);
                var current=app;until(()->current.call(runtime,"PUT","/api/advertiser/1",body,"owner","ADVERTISER",id).statusCode()==200,20);
                assertThat(app.fixture.business.root(1).status()).isEqualTo("paused");
                var original=app.call(runtime,"PUT","/api/advertiser/1",body,"owner","ADVERTISER",id);
                assertThat(JSON.readTree(original.body())).isEqualTo(JSON.readTree(app.fixture.local.command(CatalogProtocol.CommandKey.advertiser(SOURCE,1,id)).resultBytes()));
                assertThat(app.call(runtime,"GET","/api/advertiser/1/catalog-commands/"+id,null,"stranger","ADMIN",null).statusCode()).isEqualTo(403);
                assertThat(app.call(runtime,"PUT","/api/advertiser/1","{\"status\":\"active\"}","editor","ADVERTISER",UUID.randomUUID()).statusCode()).isEqualTo(403);
                Files.writeString(evidence.resolve("result.json"),JSON.writeValueAsString(Map.of("status","PASS","scenario",scenario,"scope","real SDK/auth-platform/isolated SpiceDB and status HTTP manage policy","sharedEnvironment",false)));
                System.out.println("PASS: real status fanout manage authorization; evidence="+evidence);return;
            }
            if(!rootOnly) {
            assertThat(app.call(runtime,"PUT","/api/advertiser/ad/102","{\"title\":\"真实edit\"}","editor","ADVERTISER",UUID.randomUUID()).statusCode()).isEqualTo(200);
            assertThat(app.call(runtime,"PUT","/api/advertiser/ad/102","{\"title\":\"不允许view代edit\"}","reviewer","ADVERTISER",UUID.randomUUID()).statusCode()).isEqualTo(403);
            assertThat(app.call(runtime,"PUT","/api/advertiser/1","{\"name\":\"不允许edit代manage\"}","editor","ADVERTISER",UUID.randomUUID()).statusCode()).isEqualTo(403);
            assertThat(app.call(runtime,"PUT","/api/advertiser/ad/102","{\"title\":\"不能仅靠ADMIN\"}","stranger","ADMIN",UUID.randomUUID()).statusCode()).isEqualTo(403);
            assertThat(app.call(runtime,"POST","/api/advertiser/ad/102/review?decision=approve",null,"reviewer","ADVERTISER",null).statusCode()).isEqualTo(200);
            assertThat(app.call(runtime,"PUT","/api/advertiser/1","{\"name\":\"真实manage\",\"dailyBudget\":20}","owner","ADVERTISER",UUID.randomUUID()).statusCode()).isEqualTo(200);
            }
            UUID create=UUID.randomUUID();String body="{\"name\":\"原actor开户\",\"dailyBudget\":10}";dropRelationships.set(true);
            var unknown=app.call(runtime,"POST","/api/advertiser",body,"creator","ADMIN",create);assertThat(unknown.statusCode()).isEqualTo(503);assertThat(lost.get()).isTrue();
            var command=app.fixture.local.command(CatalogProtocol.CommandKey.root(SOURCE,create));assertThat(command.phase()).isEqualTo("AUTHZ");assertThat(command.actor()).isEqualTo("creator");
            assertThat(app.call(runtime,"POST","/api/advertiser",body,"other-admin","ADMIN",create).statusCode()).isEqualTo(409);
            assertThat(app.call(runtime,"GET","/api/advertiser/catalog-commands/"+create,null,"creator","ADMIN",null).statusCode()).isEqualTo(200);
            assertThat(app.call(runtime,"GET","/api/advertiser/catalog-commands/"+create,null,"other-admin","ADMIN",null).statusCode()).isEqualTo(404);
            dropRelationships.set(false);Thread.sleep(1300);
            var resumed=app.call(runtime,"POST","/api/advertiser",body,"creator","ADMIN",create);assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
            long owner=JSON.readTree(resumed.body()).get("advertiserId").longValue();assertThat(owner).isEqualTo(command.advertiserId());
            relations(List.of(relation("platform","recsys","admin","user","creator","DELETE")));
            assertThat(fullCheck("creator","manage","advertiser",Long.toString(owner))).isTrue();
            assertThat(app.call(runtime,"PUT","/api/advertiser/"+owner,"{\"name\":\"原actor仍是owner\"}","creator","ADVERTISER",null).statusCode()).isEqualTo(200);
            if(!rootOnly) {
            UUID delete=UUID.randomUUID();assertThat(app.call(runtime,"DELETE","/api/advertiser/ad/102",null,"owner","ADVERTISER",delete).statusCode()).isEqualTo(200);
            relations(List.of(relation("advertiser","1","owner","user","owner","DELETE")));
            assertThat(fullCheck("owner","edit","advertiser","1")).isFalse();
            // 沿用原管理面minimizeLatency/atLeastAsFresh策略；不借本片改为资金入口的full策略或宣称即时撤权。
            var currentApp=app;until(()->currentApp.call(runtime,"DELETE","/api/advertiser/ad/102",null,"owner","ADVERTISER",delete).statusCode()==403,20);
            server.destroy();assertThat(server.waitFor(10,TimeUnit.SECONDS)).isTrue();
            assertThat(app.call(runtime,"PUT","/api/advertiser/1","{\"name\":\"依赖停机拒绝\"}","editor","ADVERTISER",UUID.randomUUID()).statusCode()).isEqualTo(503);
            }
            Files.writeString(evidence.resolve("result.json"),JSON.writeValueAsString(Map.of("status","PASS","scenario",scenario,"scope","real SDK/auth-platform/isolated SpiceDB and product HTTP","relationshipResponseLoss",true,"sharedEnvironment",false)));
            System.out.println("PASS: real catalog authz, original actor/root identity and relationship response loss; evidence="+evidence);
        } finally {
            if(app!=null)app.stopChildren();if(fixtureStarted)CatalogApplicationTest.closeFixture();
            if(proxy!=null)proxy.stop(0);if(proxyWorkers!=null)proxyWorkers.shutdownNow();
            if(server!=null && server.isAlive()){server.destroy();if(!server.waitFor(10,TimeUnit.SECONDS)){server.destroyForcibly();server.waitFor(10,TimeUnit.SECONDS);}}
            for(var container:List.of(ZERO,ONE,KAFKA,CatalogInitializationTest.REDIS,spice))if(container.isRunning())container.stop();
        }
    }
    private static Map<String,Object> relation(String type,String id,String name,String subjectType,String subject,String operation) {
        return Map.of("operation",operation,"resource",Map.of("type",type,"id",id),"relation",name,"subject",Map.of("type",subjectType,"id",subject));
    }
    private static void relations(List<Map<String,Object>> updates) throws Exception {assertThat(send(authUrl,"/v1/relationships",Map.of("updates",updates),token).statusCode()).isEqualTo(200);}
    private static boolean fullCheck(String subject,String permission,String type,String id) throws Exception {
        var response=send(authUrl,"/v1/check",Map.of("subject",Map.of("type","user","id",subject),"resource",Map.of("type",type,"id",id),"permission",permission,"consistency",Map.of("mode","full")),token);
        assertThat(response.statusCode()).isEqualTo(200);return JSON.readTree(response.body()).get("allowed").booleanValue();
    }
    private static HttpResponse<String> send(String base,String path,Object body,String key) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json");
        if(key!=null)builder.header("Authorization","Bearer "+key);
        return CLIENT.send(body==null?builder.GET().build():builder.POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private interface Probe{boolean ready() throws Exception;}
    private static void until(Probe probe,int seconds) throws Exception {long deadline=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();while(System.nanoTime()<deadline){try{if(probe.ready())return;}catch(Exception pending){}Thread.sleep(100);}throw new IllegalStateException("独占权限依赖启动超时");}
    private static String required(String name){String value=System.getenv(name);if(value==null || value.isBlank())throw new IllegalArgumentException("缺少隔离验收输入："+name);return value;}
}
