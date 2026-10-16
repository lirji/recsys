package com.recsys.adserving.grpc;

import org.mybatis.spring.SqlSessionTemplate;
import com.recsys.persistence.spring.SpringSqlSessions;
import com.fasterxml.jackson.databind.*;
import com.recsys.ad.*;
import com.recsys.ad.billing.ClickPreparationService;
import com.lrj.authz.protocol.*;
import com.lrj.authz.sdk.RemoteAuthzEngine;
import com.recsys.advertiser.authz.AdvertiserAuthz;
import com.recsys.advertiser.billing.*;
import com.recsys.proto.conversion.v1.SandboxConversionServiceGrpc;
import com.recsys.proto.click.v1.SandboxClickServiceGrpc;
import com.recsys.proto.grpc.GrpcDeadlineClientInterceptor;
import com.recsys.proto.security.InternalAuthGrpcClientInterceptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.atomic.AtomicReference;
import com.recsys.ad.billing.ClickSettlementService;
import com.recsys.ad.delivery.DeliveryTokenCodec;
import com.recsys.common.ad.*;
import com.recsys.common.ad.billing.*;
import com.recsys.common.query.*;
import com.recsys.recengine.ad.*;
import com.recsys.recengine.delivery.*;
import com.recsys.recengine.experiment.ExperimentService;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
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


/** CPC/CPA 浏览器独占宿主；生产控制器、交付事务、资金事务及可切换 TCP RPC，候选来自隔离数据库。 */
@EnabledIfSystemProperty(named="recsys.actions.fixture",matches="true")
class ClickConversionBrowserFixtureTest {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static State state;
    @Test void serveUntilBrowserFinishes() throws Exception {
        try(var pg=new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            try(var owned=new State(pg)) {
                state=owned;
                try(var context=(ServletWebServerApplicationContext)SpringApplication.run(Web.class,
                        "--spring.config.location=optional:classpath:/browser-actions-isolated.yml",
                        "--spring.cloud.nacos.config.import-check.enabled=false",
                        "--server.address=127.0.0.1","--server.port=0","--server.tomcat.threads.max=8",
                        "--recsys.ad.billing.sandbox-enabled=true","--recsys.ad.billing.clicks-enabled=true",
                        "--recsys.ad.billing.click-settlement-enabled=true","--recsys.ad.billing.click-delivery-enabled=true",
                        "--recsys.ad.billing.conversions-enabled=true","--recsys.ad.billing.conversion-settlement-enabled=true","--recsys.ad.billing.conversion-delivery-enabled=true")) {
                    Path output=Path.of(System.getProperty("recsys.browser.fixture-info"));
                    Files.createDirectories(output.toAbsolutePath().getParent());
                    Files.writeString(output,JSON.writeValueAsString(Map.of("url","http://127.0.0.1:"+context.getWebServer().getPort(),
                            "token",state.token("browser-admin"),"otherToken",state.token("other-admin"),"advertiserToken",state.token("browser-editor"))));
                    assertTrue(state.done.await(10,TimeUnit.MINUTES),"浏览器未完成");
                    long count=state.click.ad.queryForObject("SELECT count(*) FROM ad_click_ledger",Long.class);
                    assertTrue(count>0,"浏览器未形成真实点击账本");
                    assertEquals(count,state.click.ad.queryForObject("SELECT count(*) FROM ad_click_outbox",Long.class));
                    assertEquals(0,state.click.ad.queryForObject("SELECT count(*) FROM ad_billing_ledger",Long.class));
                    BigDecimal ledger=state.click.ad.queryForObject("SELECT sum(amount) FROM ad_click_ledger",BigDecimal.class);
                    BigDecimal charged=state.click.ad.queryForObject("SELECT sum(charged_amount) FROM ad_budget_account",BigDecimal.class);
                    assertEquals(0,ledger.compareTo(charged),"真实预算扣费与账本一致");
                    long converted=state.conversion.ad.queryForObject("SELECT count(*) FROM ad_conversion_ledger",Long.class);
                    assertTrue(converted>0,"浏览器未形成 CPA 账本");
                    assertEquals(converted,state.conversion.ad.queryForObject("SELECT count(*) FROM ad_conversion_outbox",Long.class));
                    assertEquals(0,state.conversion.ad.queryForObject("SELECT count(*) FROM ad_click_ledger",Long.class));
                    BigDecimal convertedAmount=state.conversion.ad.queryForObject("SELECT sum(amount) FROM ad_conversion_ledger",BigDecimal.class);
                    BigDecimal conversionCharged=state.conversion.ad.queryForObject("SELECT sum(charged_amount) FROM ad_budget_account",BigDecimal.class);
                    assertEquals(0,convertedAmount.compareTo(conversionCharged));
                }
            }
        }
    }
    @Configuration(proxyBeanMethods=false) @EnableWebSecurity
    @Import({ServletWebServerFactoryAutoConfiguration.class,DispatcherServletAutoConfiguration.class,WebMvcAutoConfiguration.class,
            JacksonAutoConfiguration.class,HttpMessageConvertersAutoConfiguration.class,
            SandboxClickDeliveryController.class,SandboxClickReceiptController.class,SandboxConversionDeliveryController.class,SandboxConversionImpressionController.class,SandboxConversionController.class,FixtureController.class})
    static class Web {
        @Bean AdServingGateway gateway(){return state.gateway;}
        @Bean ClickPreparationService clickPreparations(){return state.click.preparations;}
        @Bean ClickSettlementService clickSettlements(){return state.click.settlements;}
        @Bean("clickDeliveryOperationService") DeliveryOperationService clicks(){return state.click.delivery(state.gateway);}
        @Bean("conversionDeliveryOperationService") DeliveryOperationService conversions(){return state.conversion.delivery(state.gateway);}
        @Bean AdvertiserAuthz advertiserAuthz(){return new AdvertiserAuthz("disabled",ConversionOnlineFixture.provider(state.authz));}
        @Bean GrpcSandboxConversionClient conversionClient(){
            var client=new GrpcSandboxConversionClient();
            var stub=SandboxConversionServiceGrpc.newBlockingStub(state.conversion.channel).withInterceptors(new GrpcDeadlineClientInterceptor(20000))
                    .withInterceptors(new InternalAuthGrpcClientInterceptor("x-internal-token","recsys-advertiser","SERVICE",60,ConversionOnlineFixture.SECRET));
            ReflectionTestUtils.setField(client,"stub",stub);return client;
        }
        @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.csrf(c->c.disable()).authorizeHttpRequests(a->a.anyRequest().permitAll())
                    .addFilterBefore(new OncePerRequestFilter() {
                        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
                                throws java.io.IOException,ServletException {
                            if(!request.getRequestURI().startsWith("/api/")){chain.doFilter(request,response);return;}
                            String subject=state.subject(request.getHeader("Authorization"));
                            if(subject==null){response.sendError(401);return;}
                            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(subject,"",List.of(new SimpleGrantedAuthority(subject.equals("browser-editor")?"ROLE_ADVERTISER":"ROLE_ADMIN"))));
                            try{chain.doFilter(request,response);}finally{SecurityContextHolder.clearContext();}
                        }
                    },AnonymousAuthenticationFilter.class).build();
        }
    }
    @RestController static class FixtureController {
        record Totals(long ledgerCount,long outboxCount,String amount) {}
        /** 只绑定 loopback 且仅存在测试 classpath；每个浏览器操作核对其真实账本与 Outbox。 */
        @GetMapping("/fixture/click/{id}") Totals totals(@PathVariable UUID id) {
            return state.click.ad.queryForObject("""
                    SELECT count(*),count(o.event_id),coalesce(sum(l.amount),0)
                    FROM ad_click_ledger l LEFT JOIN ad_click_outbox o ON o.event_id=l.ledger_id WHERE l.operation_id=?
                    """,(rs,n)->new Totals(rs.getLong(1),rs.getLong(2),rs.getBigDecimal(3).toPlainString()),id);
        }
        /** 详情页的非资金资料也从本轮种子/账户读取，不硬编码页面演示数据。 */
        @GetMapping("/api/advertiser/{id}") Map<String,Object> advertiser(@PathVariable long id) {
            return Map.of("advertiserId",Long.toString(id),"name",state.conversion.ad.queryForObject("SELECT title FROM browser_conversion_seed LIMIT 1",String.class),
                    "status","active","dailyBudget",10,"spentToday",0,"remainingBudget",10);
        }
        @GetMapping("/fixture/conversion/{id}") Totals conversionTotals(@PathVariable UUID id) {
            return state.conversion.ad.queryForObject("""
                    SELECT count(*),count(o.event_id),coalesce(sum(l.amount),0)
                    FROM ad_conversion_ledger l LEFT JOIN ad_conversion_outbox o ON o.event_id=l.ledger_id WHERE l.operation_id=?
                    """,(rs,n)->new Totals(rs.getLong(1),rs.getLong(2),rs.getBigDecimal(3).toPlainString()),id);
        }
        /** 故障注入只影响隔离权限替身；真实 SDK 模式不允许此接口改变权限。 */
        @PostMapping("/fixture/authz/{mode}") void permission(@PathVariable String mode){
            if(System.getProperty("recsys.actions.authz-url")!=null)throw new IllegalStateException("真实权限模式不可注入替身");
            if(!Set.of("allow","deny","failure").contains(mode))throw new IllegalArgumentException();
            state.authzMode.set(mode);
        }
        @PostMapping("/fixture/finish") void finish(){state.done.countDown();}
    }
    static class State implements AutoCloseable {
        final ClickOnlineFixture click;
        final ConversionOnlineFixture conversion;
        final AuthzEngine authz;
        final AtomicReference<String> authzMode=new AtomicReference<>("allow");
        final AdServingGateway gateway;
        final CountDownLatch done=new CountDownLatch(1);
        final byte[] key=new byte[32];
        State(PostgreSQLContainer<?> pg) throws Exception {
            new java.security.SecureRandom().nextBytes(key);
            click=new ClickOnlineFixture(pg);
            // 浏览器必须真实等待签发后的连续可见时间，不使用单元夹具的时钟偏移。
            click.ad.execute("DROP TRIGGER test_click_clock ON ad_click_quote");
            click.ad.execute("CREATE TABLE browser_click_seed(ad_id bigint PRIMARY KEY,title text NOT NULL,amount numeric(20,6) NOT NULL)");
            click.ad.execute("COMMENT ON TABLE browser_click_seed IS '独占浏览器 CPC 候选种子'");
            click.ad.execute("COMMENT ON COLUMN browser_click_seed.ad_id IS '大整数广告标识'");
            click.ad.execute("COMMENT ON COLUMN browser_click_seed.title IS '数据库创意正文'");
            click.ad.execute("COMMENT ON COLUMN browser_click_seed.amount IS '人民币单次点击报价'");
            click.ad.update("INSERT INTO browser_click_seed VALUES (?, ?, 0.123456),(?, ?, 0.000000)",Long.MAX_VALUE-1,"协作空间 · 让团队沟通更清楚",Long.MAX_VALUE-2,"知识检索 · 从可信内容开始");
            when(click.pipeline.sandboxClickCandidates(anyLong(),any(),anyInt(),anyString(),anyDouble())).thenAnswer(i->click.ad.query(
                    "SELECT * FROM browser_click_seed ORDER BY ad_id DESC LIMIT ?",(rs,n)->{
                        long id=rs.getLong("ad_id");
                        var ad=new SponsoredAd(id,id,Long.MAX_VALUE,0,rs.getString("title"),AdChannel.KW_EXACT,1,1,1,1,1,1,1,1,0,"OCPC");
                        var price=new ClickPrice(BidType.OCPC,new GspPrice(rs.getBigDecimal("amount"),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE));
                        return new BiddingService.ClickQuotedAd(ad,price);
                    },(int)i.getArgument(2)));
            boolean remote="grpc".equals(System.getProperty("recsys.actions.mode"));
            var clickGateway=click.gateway(remote,click.preparations,click.settlements);
            conversion=new ConversionOnlineFixture(pg);
            conversion.ad.execute("DROP TRIGGER test_conversion_clock ON ad_conversion_quote");
            conversion.ad.execute("CREATE TABLE browser_conversion_seed(ad_id bigint PRIMARY KEY,title text NOT NULL,amount numeric(20,6) NOT NULL)");
            conversion.ad.execute("COMMENT ON TABLE browser_conversion_seed IS '独占浏览器 CPA 候选种子'");
            conversion.ad.execute("COMMENT ON COLUMN browser_conversion_seed.ad_id IS '大整数广告标识'");
            conversion.ad.execute("COMMENT ON COLUMN browser_conversion_seed.title IS '数据库创意正文'");
            conversion.ad.execute("COMMENT ON COLUMN browser_conversion_seed.amount IS '人民币单次业务转化报价'");
            conversion.ad.update("INSERT INTO browser_conversion_seed VALUES (?, ?, 0.234567)",Long.MAX_VALUE-5,"业务转化 · 从有效展示到真实结果");
            when(conversion.pipeline.sandboxConversionCandidates(anyLong(),any(),anyInt(),anyString(),anyDouble())).thenAnswer(i->conversion.ad.query(
                    "SELECT * FROM browser_conversion_seed ORDER BY ad_id LIMIT ?",(rs,n)->{
                        long id=rs.getLong("ad_id");
                        var ad=new SponsoredAd(id,id,Long.MAX_VALUE,0,rs.getString("title"),AdChannel.KW_EXACT,1,1,1,1,1,1,1,1,0,"CPA");
                        return new BiddingService.ConversionQuotedAd(ad,new ConversionPrice(new GspPrice(rs.getBigDecimal("amount"),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE)));
                    },(int)i.getArgument(2)));
            var conversionGateway=conversion.gateway(remote,conversion.preparations,conversion.settlements);
            // 浏览器套件长于单条单测；每次 RPC 有独立截止时间，不能复用创建时的绝对 deadline。
            if(remote) {
                ReflectionTestUtils.setField(clickGateway,"clickStub",SandboxClickServiceGrpc.newBlockingStub(click.channel).withInterceptors(
                        new GrpcDeadlineClientInterceptor(20000),new InternalAuthGrpcClientInterceptor("x-internal-token","recsys-rec-engine","SERVICE",60,ClickOnlineFixture.SECRET)));
                ReflectionTestUtils.setField(conversionGateway,"conversionStub",SandboxConversionServiceGrpc.newBlockingStub(conversion.channel).withInterceptors(
                        new GrpcDeadlineClientInterceptor(20000),new InternalAuthGrpcClientInterceptor("x-internal-token","recsys-rec-engine","SERVICE",60,ConversionOnlineFixture.SECRET)));
            }
            // 两种协议分别持有真实 owner fixture；仅在测试入口按方法路由，不替换任何资金方法结果。
            gateway=mock(AdServingGateway.class, invocation->{
                Object target=invocation.getMethod().getName().contains("Conversion")?conversionGateway:clickGateway;
                try{return invocation.getMethod().invoke(target,invocation.getArguments());}
                catch(java.lang.reflect.InvocationTargetException ex){throw ex.getCause();}
            });
            String authzUrl=System.getProperty("recsys.actions.authz-url");
            if(authzUrl!=null)authz=new RemoteAuthzEngine(authzUrl,System.getenv("ACTIONS_AUTHZ_TOKEN"));
            else {
                authz=mock(AuthzEngine.class);
                when(authz.check(any(),anyString(),any(),any())).thenAnswer(i->{
                    assertEquals("edit",i.getArgument(1));assertEquals(Consistency.fullyConsistent(),i.getArgument(3));
                    if(authzMode.get().equals("failure"))throw new IllegalStateException("isolated authz unavailable");
                    return authzMode.get().equals("allow") && Set.of(SubjectRef.user("browser-admin"),SubjectRef.user("browser-editor")).contains(i.getArgument(0))
                            && ResourceRef.of("advertiser",Long.toString(Long.MAX_VALUE)).equals(i.getArgument(2));
                });
            }
        }
        @Override public void close() throws Exception {click.close();conversion.close();}
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
                return Set.of("browser-admin","other-admin","browser-editor").contains(subject)?subject:null;
            }catch(Exception ex){return null;}
        }
        byte[] mac(String text) throws Exception {var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));}
        String encode(String text){return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));}
    }
}
