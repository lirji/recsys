package com.recsys.ad.billing;

import org.mybatis.spring.SqlSessionTemplate;
import com.recsys.persistence.spring.SpringSqlSessions;
import com.fasterxml.jackson.databind.*;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.recsys.ad.*;
import com.recsys.ad.delivery.DeliveryTokenCodec;
import com.recsys.common.ad.*;
import com.recsys.common.ad.billing.*;
import com.recsys.common.query.*;
import com.recsys.recengine.ad.*;
import com.recsys.recengine.delivery.*;
import com.recsys.recengine.experiment.ExperimentService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * opt-in 浏览器联调宿主：生产 HTTP/交付/资金/事件代码，真实隔离 PG/Kafka。
 * 仅候选模型/查询理解与登录为测试适配；候选内容和价格从数据库种子读取，页面不硬编码。
 */
@EnabledIfSystemProperty(named="recsys.browser.fixture",matches="true")
class SandboxExposureBrowserFixtureTest {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static State state;

    @Test void serveOwnedSandboxUntilBrowserFinishes() throws Exception {
        try(var pg=new PostgreSQLContainer<>("postgres:16-alpine");var kafka=new KafkaFixture()) {
            pg.start();kafka.start();state=new State(pg,kafka.bootstrap());
            var app=new SpringApplication(Web.class);
            try(var context=(ServletWebServerApplicationContext)app.run(
                    "--spring.config.location=optional:classpath:/browser-fixture-not-present.yml",
                    "--spring.cloud.nacos.config.import-check.enabled=false",
                    "--server.address=127.0.0.1","--server.port=0","--server.tomcat.threads.max=8",
                    "--recsys.ad.billing.sandbox-enabled=true","--recsys.ad.billing.receipts-enabled=true")) {
                state.startEvents();
                Path output=Path.of(System.getProperty("recsys.browser.fixture-info","target/browser-fixture.json"));
                Files.createDirectories(output.toAbsolutePath().getParent());
                Files.writeString(output,JSON.writeValueAsString(Map.of("url","http://127.0.0.1:"+context.getWebServer().getPort(),
                        "token",state.token("browser-admin"),"otherToken",state.token("other-admin"))));
                assertTrue(state.done.await(10,TimeUnit.MINUTES),"浏览器未完成或未调用隔离 fixture finish");
                assertTrue(state.ad.queryForObject("SELECT count(*) FROM ad_billing_ledger",Long.class)>0,"未发生真实浏览器计费");
                assertEquals(0,state.ad.queryForObject("""
                        SELECT count(*) FROM ad_billing_ledger l LEFT JOIN ad_billing_projection_fact p ON p.event_id=l.ledger_id
                        WHERE p.event_id IS NULL
                        """,Long.class),"每条已收费事实须已投影");
                BigDecimal charged=state.ad.queryForObject("SELECT sum(charged_amount) FROM ad_budget_account",BigDecimal.class);
                BigDecimal ledger=state.ad.queryForObject("SELECT sum(amount) FROM ad_billing_ledger",BigDecimal.class);
                assertEquals(0,charged.compareTo(ledger));
            } finally {state.close();}
        }
    }

    @Configuration(proxyBeanMethods=false) @EnableWebSecurity
    @Import({ServletWebServerFactoryAutoConfiguration.class,DispatcherServletAutoConfiguration.class,WebMvcAutoConfiguration.class,
            JacksonAutoConfiguration.class,HttpMessageConvertersAutoConfiguration.class,
            SandboxDeliveryController.class,SandboxImpressionController.class,FixtureController.class})
    static class Web {
        @Bean AdPipeline pipeline(){return state.pipeline;}
        @Bean SandboxPreparationService preparations(){return state.preparation;}
        @Bean ImpressionReceiptService receipts(){return state.receipt;}
        @Bean AdServingGateway gateway(AdPipeline pipeline){return new InProcessAdServingGateway(pipeline);}
        @Bean("deliveryOperationService") DeliveryOperationService operations(AdServingGateway gateway){
            QueryUnderstandingService queries=(query,user)->new StructuredQuery(query,query,null,null,null,null);
            return new DeliveryOperationService(state.operations,queries,mock(ExperimentService.class),new AdProperties(),gateway);
        }
        @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.csrf(c->c.disable()).authorizeHttpRequests(a->a.anyRequest().permitAll())
                    .addFilterBefore(new OncePerRequestFilter() {
                        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
                                throws java.io.IOException,ServletException {
                            if(!request.getRequestURI().startsWith("/api/sandbox/")){chain.doFilter(request,response);return;}
                            String subject=state.subject(request.getHeader("Authorization"));
                            if(subject==null){response.sendError(401);return;}
                            var auth=new UsernamePasswordAuthenticationToken(subject,"",List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
                            SecurityContextHolder.getContext().setAuthentication(auth);
                            try{chain.doFilter(request,response);}finally{SecurityContextHolder.clearContext();}
                        }
                    },AnonymousAuthenticationFilter.class).build();
        }
    }

    @RestController static class FixtureController {
        record Totals(long ledgerCount,long projectedCount,String amount) {}
        /** 仅存在于测试 classpath 且宿主仅绑定 loopback；用真实表核对页面回执。 */
        @GetMapping("/fixture/operation/{id}") Totals totals(@PathVariable UUID id) {
            return state.ad.queryForObject("""
                    SELECT count(*),count(p.event_id),coalesce(sum(l.amount),0)
                    FROM ad_billing_ledger l LEFT JOIN ad_billing_projection_fact p ON p.event_id=l.ledger_id
                    WHERE l.operation_id=?
                    """,(rs,n)->new Totals(rs.getLong(1),rs.getLong(2),rs.getBigDecimal(3).toPlainString()),id);
        }
        @PostMapping("/fixture/finish") void finish(){state.done.countDown();}
    }

    static class State implements AutoCloseable {
        final JdbcTemplate ad;
        final DeliveryOperationRepository operations;
        final AdPipeline pipeline=mock(AdPipeline.class);
        final SandboxPreparationService preparation;
        final ImpressionReceiptService receipt;
        final CountDownLatch done=new CountDownLatch(1);
        final byte[] key=new byte[32];
        final BillingKafkaPublisher publisher;
        final BillingOutboxJob relay;
        final BillingProjectionConsumer consumer;
        final ThreadPoolTaskScheduler scheduler=new ThreadPoolTaskScheduler();
        State(PostgreSQLContainer<?> pg,String bootstrap) throws Exception {
            new java.security.SecureRandom().nextBytes(key);
            var admin=new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()));
            admin.execute("CREATE SCHEMA browser_ad");admin.execute("CREATE SCHEMA browser_engine");
            var adDs=new DriverManagerDataSource(pg.getJdbcUrl()+"&currentSchema=browser_ad",pg.getUsername(),pg.getPassword());
            var engineDs=new DriverManagerDataSource(pg.getJdbcUrl()+"&currentSchema=browser_engine",pg.getUsername(),pg.getPassword());
            ad=new JdbcTemplate(adDs);
            for(String path:List.of(BudgetRepository.MIGRATION,CpmQuoteRepository.MIGRATION,ReservationRepository.MIGRATION,
                    PreparationRepository.MIGRATION,ImpressionReceiptRepository.MIGRATION,BillingOutboxRepository.MIGRATION))migrate(adDs,path,BudgetRepository.migrationSha256(path));
            migrate(engineDs,DeliveryOperationRepository.MIGRATION,DeliveryOperationRepository.migrationSha256());
            operations=new DeliveryOperationRepository(new JdbcTemplate(engineDs),JSON);operations.verifySchema();
            // 独立种子表不进入产品 schema，所有测试字段也有注释。
            ad.execute("CREATE TABLE browser_ad_seed(ad_id bigint PRIMARY KEY,title text NOT NULL,amount numeric(20,6) NOT NULL)");
            ad.execute("COMMENT ON TABLE browser_ad_seed IS '浏览器联调专用候选种子'");
            ad.execute("COMMENT ON COLUMN browser_ad_seed.ad_id IS '独立广告标识'");
            ad.execute("COMMENT ON COLUMN browser_ad_seed.title IS '实际展示的文本创意'");
            ad.execute("COMMENT ON COLUMN browser_ad_seed.amount IS '隔离 CPM 报价，人民币每次曝光'");
            ad.update("INSERT INTO browser_ad_seed VALUES (1,?,0.010001),(2,?,0.020002),(3,?,0.030003)",
                    "云端办公 · 团队协作从容开始","企业知识检索 · 快速找到可信答案","数据分析平台 · 让业务决策有据可依");
            when(pipeline.sandboxCandidates(anyLong(),any(),anyInt(),anyString(),anyDouble())).thenAnswer(invocation->ad.query(
                    "SELECT * FROM browser_ad_seed ORDER BY ad_id LIMIT ?",(rs,n)-> {
                        long id=rs.getLong("ad_id");
                        var item=new SponsoredAd(id,id,1,0,rs.getString("title"),AdChannel.KW_EXACT,1,1,1,1,1,1,1,1,id,"CPM");
                        return new BiddingService.QuotedAd(item,new CpmPrice(rs.getBigDecimal("amount"),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE));
                    },(int)invocation.getArgument(2)));
            var budgets=new BudgetAccountService(new BudgetRepository(new SqlSessionTemplate(SpringSqlSessions.create(ad.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml"))),BudgetConfiguration.transactions(adDs,5));
            budgets.setLimit(BudgetCommand.parse(1,UUID.randomUUID().toString(),LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(),"CNY","100",0,"fixture"));
            var quotes=new CpmQuoteRepository(new SqlSessionTemplate(SpringSqlSessions.create(ad.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml")));var reservations=new ReservationRepository(new SqlSessionTemplate(SpringSqlSessions.create(ad.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml")));var prepared=new PreparationRepository(new SqlSessionTemplate(SpringSqlSessions.create(ad.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml")),JSON);
            var tokens=new DeliveryTokenCodec(JSON,"browser",Map.of("browser",Base64.getEncoder().encodeToString(key)));
            preparation=new SandboxPreparationService(pipeline,prepared,quotes,new ReservationService(reservations,quotes,BudgetConfiguration.transactions(adDs,5),true),tokens);
            receipt=new ImpressionReceiptService(new ImpressionReceiptRepository(new SqlSessionTemplate(SpringSqlSessions.create(ad.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml")),JSON),prepared,reservations,quotes,tokens,BudgetConfiguration.transactions(adDs,5),true);
            String topic="browser-billing-"+UUID.randomUUID();
            try(var kafkaAdmin=Admin.create(Map.of("bootstrap.servers",bootstrap))){
                kafkaAdmin.createTopics(List.of(new NewTopic(topic,2,(short)1).configs(Map.of("cleanup.policy","delete","retention.ms","-1")))).all().get(15,TimeUnit.SECONDS);
            }
            var env=new MockEnvironment().withProperty("spring.kafka.bootstrap-servers",bootstrap)
                    .withProperty("recsys.ad.billing.events.topic",topic).withProperty("recsys.ad.billing.events.group","browser-"+UUID.randomUUID());
            var metrics=new SimpleMeterRegistry();var outbox=new BillingOutboxRepository(new SqlSessionTemplate(SpringSqlSessions.create(adDs, "classpath*:mapper/ad/addb/**/*.xml")));var codec=new BillingEventCodec(JSON);
            publisher=new BillingKafkaPublisher(new KafkaProducer<>(BillingEventConfiguration.producerProperties(env)),topic);
            scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("browser-outbox-");scheduler.initialize();
            relay=new BillingOutboxJob(outbox,new BillingOutboxRelay(outbox,publisher,codec,metrics),scheduler,metrics);
            consumer=new BillingProjectionConsumer(()->new KafkaConsumer<>(BillingEventConfiguration.consumerProperties(env)),
                    new BillingProjectionService(new BillingProjectionRepository(new SqlSessionTemplate(SpringSqlSessions.create(adDs, "classpath*:mapper/ad/addb/**/*.xml"))),codec,BudgetConfiguration.transactions(adDs,5)),topic,metrics);
        }
        void startEvents(){relay.start();consumer.start();}
        @Override public void close(){consumer.stop();relay.stop();scheduler.shutdown();publisher.close();}
        String token(String subject) throws Exception {
            String content=encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}")+"."+encode(JSON.writeValueAsString(
                    Map.of("iss","browser-fixture","sub",subject,"exp",Instant.now().plusSeconds(3600).getEpochSecond())));
            return content+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac(content));
        }
        String subject(String authorization) {
            try {
                if(authorization==null || !authorization.startsWith("Bearer "))return null;
                String[] parts=authorization.substring(7).split("\\.");
                if(parts.length!=3 || !java.security.MessageDigest.isEqual(mac(parts[0]+"."+parts[1]),Base64.getUrlDecoder().decode(parts[2])))return null;
                var payload=JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
                if(payload.path("exp").asLong()<=Instant.now().getEpochSecond())return null;
                String subject=payload.path("sub").asText();
                return Set.of("browser-admin","other-admin").contains(subject)?subject:null;
            }catch(Exception ex){return null;}
        }
        byte[] mac(String text) throws Exception {var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));}
        String encode(String text){return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));}
        void migrate(DriverManagerDataSource ds,String path,String sha) throws Exception {
            try(var c=ds.getConnection();var statement=c.createStatement()){
                statement.execute("SET recsys.migration_sha256='"+sha+"'");
                ScriptUtils.executeSqlScript(c,new ClassPathResource(path));
            }
        }
    }
    static class KafkaFixture extends GenericContainer<KafkaFixture> {
        KafkaFixture(){
            super("apache/kafka:3.7.1");withExposedPorts(19092);
            withEnv(Map.of("KAFKA_NODE_ID","1","KAFKA_PROCESS_ROLES","broker,controller","KAFKA_CONTROLLER_QUORUM_VOTERS","1@localhost:9093",
                    "KAFKA_LISTENERS","INTERNAL://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093,HOST://0.0.0.0:19092",
                    "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP","INTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT,HOST:PLAINTEXT",
                    "KAFKA_INTER_BROKER_LISTENER_NAME","INTERNAL","KAFKA_CONTROLLER_LISTENER_NAMES","CONTROLLER",
                    "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR","1","KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS","0","KAFKA_AUTO_CREATE_TOPICS_ENABLE","false"));
            withCommand("/bin/bash","-c","while [ ! -f /tmp/browser-kafka-start ]; do sleep 0.1; done; . /tmp/browser-kafka-start; exec /etc/kafka/docker/run");
            waitingFor(Wait.forLogMessage(".*Kafka Server started.*",1).withStartupTimeout(Duration.ofSeconds(90)));
        }
        @Override protected void containerIsStarting(InspectContainerResponse info){
            copyFileToContainer(Transferable.of("export KAFKA_ADVERTISED_LISTENERS='INTERNAL://localhost:9092,HOST://"+bootstrap()+"'\n",0644),"/tmp/browser-kafka-start");
        }
        String bootstrap(){return getHost()+":"+getMappedPort(19092);}
    }
}
