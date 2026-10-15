package com.recsys.behavior.exposure;

import com.fasterxml.jackson.databind.*;
import com.recsys.behavior.persistence.mapper.primary.NaturalReceiptMapper;
import com.recsys.persistence.spring.SpringSqlSessions;
import org.mybatis.spring.SqlSessionTemplate;
import com.recsys.recengine.exposure.*;
import com.recsys.common.dto.*;
import com.recsys.common.exposure.*;
import com.recsys.content.Item;
import com.recsys.recengine.RecommendOrchestrator;
import com.recsys.recengine.content.ContentGateway;
import com.recsys.recengine.delivery.DeliveryOperationRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
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
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.testcontainers.containers.PostgreSQLContainer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * S09 opt-in 真实 owner HTTP 与 PG 浏览器宿主；两个 owner 各自数据源/schema。
 * 同 JVM 装配不冒充跨进程部署。候选读取隔离种子，登录为测试适配器，不代表生产 gateway/IdP。
 */
@EnabledIfSystemProperty(named="recsys.natural.browser.fixture",matches="true")
class NaturalExposureBrowserAcceptanceTest {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static State state;
    @Test void serveUntilBrowserFinishesAndCheckFacts() throws Exception {
        try(var pg=new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();state=new State(pg);
            try(var context=(ServletWebServerApplicationContext)new SpringApplication(Web.class).run(
                    "--spring.config.location=optional:classpath:/natural-browser-fixture-not-present.yml",
                    "--spring.cloud.nacos.config.import-check.enabled=false","--server.address=127.0.0.1","--server.port=0",
                    "--server.tomcat.threads.max=8","--recsys.natural.delivery-enabled=true","--recsys.natural.receipts-enabled=true")) {
                var output=Path.of(System.getProperty("recsys.natural.browser.fixture-info","target/natural-browser-fixture.json"));
                Files.createDirectories(output.toAbsolutePath().getParent());
                Files.writeString(output,JSON.writeValueAsString(Map.of("url","http://127.0.0.1:"+context.getWebServer().getPort(),
                        "token",state.token("browser-admin"),"otherToken",state.token("other-admin"))));
                assertTrue(state.done.await(10,TimeUnit.MINUTES),"浏览器未完成");
                assertTrue(state.behavior.queryForObject("SELECT count(*) FROM natural_impression_fact",Long.class)>0,"没有实际可见回执");
                assertEquals(0L,state.behavior.queryForObject("SELECT count(*) FROM natural_impression_fact f LEFT JOIN natural_impression_outbox o ON o.event_id=f.qualification_id WHERE o.event_id IS NULL",Long.class));
                assertEquals(0L,state.behavior.queryForObject("SELECT count(*) FROM natural_impression_outbox WHERE published_at IS NOT NULL",Long.class),"S09 不能冒充已投递");
                verify(state.recommender,never()).recommend(any());
            }
        }
    }
    @Configuration(proxyBeanMethods=false) @EnableWebSecurity
    @Import({ServletWebServerFactoryAutoConfiguration.class,DispatcherServletAutoConfiguration.class,WebMvcAutoConfiguration.class,
            JacksonAutoConfiguration.class,HttpMessageConvertersAutoConfiguration.class,
            NaturalDeliveryController.class,NaturalReceiptController.class,FixtureController.class})
    static class Web {
        @Bean NaturalDeliveryService delivery(){return state.delivery;}
        @Bean NaturalReceiptService receipt(){return state.receipt;}
        @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.csrf(c->c.disable()).authorizeHttpRequests(a->a.anyRequest().permitAll())
                    .addFilterBefore(new OncePerRequestFilter() {
                        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
                                throws java.io.IOException,ServletException {
                            if(!request.getRequestURI().startsWith("/api/sandbox/")){chain.doFilter(request,response);return;}
                            String subject=state.subject(request.getHeader("Authorization"));
                            if(subject==null){response.sendError(401);return;}
                            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(subject,"",List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
                            try{chain.doFilter(request,response);}finally{SecurityContextHolder.clearContext();}
                        }
                    },AnonymousAuthenticationFilter.class).build();
        }
    }
    @RestController static class FixtureController {
        record Totals(long factCount,long outboxCount,List<String> users,List<String> items) {}
        /** 独占 loopback 测试探针，只读实际 owner 的事实/Outbox，不作为产品 API。 */
        @GetMapping("/fixture/operation/{id}") Totals totals(@PathVariable UUID id) {
            var events=state.behavior.queryForList("SELECT payload_json FROM natural_impression_outbox WHERE payload_json::jsonb->>'operationId'=? ORDER BY event_id",String.class,id.toString());
            var users=new ArrayList<String>();var items=new ArrayList<String>();
            for(var event:events) {try{var value=JSON.readTree(event);users.add(value.path("userId").asText());items.add(value.path("itemId").asText());}catch(Exception ex){throw new IllegalStateException(ex);}}
            long facts=state.behavior.queryForObject("SELECT count(*) FROM natural_impression_fact f JOIN natural_qualification_guard g USING(qualification_id) WHERE g.context_json::jsonb->>'operationId'=?",Long.class,id.toString());
            return new Totals(facts,events.size(),users,items);
        }
        @PostMapping("/fixture/finish") void finish(){state.done.countDown();}
    }
    static class State {
        final JdbcTemplate behavior;
        final JdbcTemplate engine;
        final DriverManagerDataSource engineDs;
        final ContentGateway content;
        final NaturalQualificationToken tokens;
        final NaturalDeliveryService delivery;
        final NaturalReceiptService receipt;
        final RecommendOrchestrator recommender=mock(RecommendOrchestrator.class);
        final CountDownLatch done=new CountDownLatch(1);
        final byte[] key=new byte[32];
        State(PostgreSQLContainer<?> pg) throws Exception {
            new java.security.SecureRandom().nextBytes(key);
            var admin=new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()));
            admin.execute("CREATE SCHEMA browser_natural_engine");admin.execute("CREATE SCHEMA browser_natural_behavior");
            engineDs=new DriverManagerDataSource(pg.getJdbcUrl()+"&currentSchema=browser_natural_engine",pg.getUsername(),pg.getPassword());
            var behaviorDs=new DriverManagerDataSource(pg.getJdbcUrl()+"&currentSchema=browser_natural_behavior",pg.getUsername(),pg.getPassword());
            engine=new JdbcTemplate(engineDs);behavior=new JdbcTemplate(behaviorDs);
            migrate(engineDs,DeliveryOperationRepository.MIGRATION,DeliveryOperationRepository.migrationSha256());
            migrate(engineDs,NaturalDeliveryRepository.MIGRATION,NaturalDeliveryRepository.migrationSha256());
            migrate(behaviorDs,NaturalReceiptRepository.MIGRATION,NaturalReceiptRepository.migrationSha256());
            engine.execute("CREATE TABLE browser_natural_seed(item_id bigint PRIMARY KEY,title text NOT NULL,category text NOT NULL,description text NOT NULL)");
            engine.execute("COMMENT ON TABLE browser_natural_seed IS '浏览器联调隔离内容种子，非产品表'");
            engine.execute("COMMENT ON COLUMN browser_natural_seed.item_id IS '超过 JS 安全整数范围的真实内容 ID'");
            engine.execute("COMMENT ON COLUMN browser_natural_seed.title IS '冻结标题'");
            engine.execute("COMMENT ON COLUMN browser_natural_seed.category IS '冻结类目'");
            engine.execute("COMMENT ON COLUMN browser_natural_seed.description IS '冻结正文'");
            engine.update("INSERT INTO browser_natural_seed VALUES (9007199254740993,?,'企业实践',?),(9007199254740994,?,'架构',?),(9007199254740995,?,'搜索',?)",
                    "action · 团队知识库的日常协作","从分散的文档中找到可信答案，让团队的经验持续积累。",
                    "action · 可恢复的数据处理","保留原操作与检查点，故障后从已确认的位置继续。",
                    "action · 理解每一次搜索","结合内容与上下文，观察搜索结果是否真正可见。");
            when(recommender.naturalCandidates(any())).thenAnswer(call->{
                RecommendRequest r=call.getArgument(0);
                var items=engine.query("SELECT item_id FROM browser_natural_seed WHERE (CAST(? AS text) IS NULL OR title ILIKE ?) ORDER BY item_id LIMIT ?",
                        (rs,n)->new RecommendItem(rs.getLong(1),0.9-n*0.1,List.of("HOT"),null),r.query(),"%"+r.query()+"%",r.size());
                return new RecommendOrchestrator.NaturalCandidates(new RecommendResponse(r.userId(),r.scene(),items,"browser-trace"),"sandbox:first");
            });
            content=ids->{
                var all=engine.query("SELECT * FROM browser_natural_seed ORDER BY item_id",(rs,n)->new Item(rs.getLong("item_id"),rs.getString("title"),rs.getString("category"),List.of(),rs.getString("description"),1));
                var result=new HashMap<Long,Item>();all.stream().filter(i->ids.contains(i.itemId())).forEach(i->result.put(i.itemId(),i));return result;
            };
            tokens=new NaturalQualificationToken("browser",Map.of("browser",Base64.getEncoder().encodeToString(key)));
            var operations=new NaturalDeliveryRepository(engine,JSON);operations.verifySchema();
            // 会话模板每次调用关闭会话，工厂不拥有连接池；事务仍绑定原behaviorDs。
            var receiptMapper=new SqlSessionTemplate(SpringSqlSessions.create(behaviorDs,"classpath*:mapper/behavior/primary/**/*.xml")).getMapper(NaturalReceiptMapper.class);
            var receipts=new NaturalReceiptRepository(receiptMapper,behaviorDs,JSON);receipts.verifySchema();
            delivery=new NaturalDeliveryService(operations,recommender,content,tokens,NaturalQualification.TEXT_POLICY_V1,true);
            receipt=new NaturalReceiptService(receipts,tokens,NaturalQualification.TEXT_POLICY_V1,new DataSourceTransactionManager(behaviorDs),true);
        }
        /** 测试签名身份只允许本宿主两个主体，随机 key 不写入源码或页面。 */
        String token(String subject) throws Exception {
            String value=encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}")+"."+encode(JSON.writeValueAsString(Map.of("iss","natural-browser-fixture","sub",subject,"exp",Instant.now().plusSeconds(3600).getEpochSecond())));
            return value+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac(value));
        }
        String subject(String authorization) {
            try {
                if(authorization==null||!authorization.startsWith("Bearer "))return null;
                String[] parts=authorization.substring(7).split("\\.");
                if(parts.length!=3||!java.security.MessageDigest.isEqual(mac(parts[0]+"."+parts[1]),Base64.getUrlDecoder().decode(parts[2])))return null;
                var payload=JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));String subject=payload.path("sub").asText();
                return payload.path("exp").asLong()>Instant.now().getEpochSecond()&&Set.of("browser-admin","other-admin").contains(subject)?subject:null;
            }catch(Exception ex){return null;}
        }
        byte[] mac(String text) throws Exception {var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));}
        String encode(String text){return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));}
        void migrate(DriverManagerDataSource ds,String path,String sha) throws Exception {
            try(var c=ds.getConnection();var statement=c.createStatement();var in=new ClassPathResource(path).getInputStream()) {
                statement.execute("SET recsys.migration_sha256='"+sha+"'");statement.execute(new String(in.readAllBytes(),StandardCharsets.UTF_8));
            }
        }
    }
}
