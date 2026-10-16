package com.recsys.adserving.grpc;

import org.mybatis.spring.SqlSessionTemplate;
import com.recsys.persistence.spring.SpringSqlSessions;
import com.fasterxml.jackson.databind.*;
import com.recsys.ad.*;
import com.recsys.ad.billing.ClickPreparationService;
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
                        "--recsys.ad.billing.click-settlement-enabled=true","--recsys.ad.billing.click-delivery-enabled=true")) {
                    Path output=Path.of(System.getProperty("recsys.browser.fixture-info"));
                    Files.createDirectories(output.toAbsolutePath().getParent());
                    Files.writeString(output,JSON.writeValueAsString(Map.of("url","http://127.0.0.1:"+context.getWebServer().getPort(),
                            "token",state.token("browser-admin"),"otherToken",state.token("other-admin"))));
                    assertTrue(state.done.await(10,TimeUnit.MINUTES),"浏览器未完成");
                    long count=state.click.ad.queryForObject("SELECT count(*) FROM ad_click_ledger",Long.class);
                    assertTrue(count>0,"浏览器未形成真实点击账本");
                    assertEquals(count,state.click.ad.queryForObject("SELECT count(*) FROM ad_click_outbox",Long.class));
                    assertEquals(0,state.click.ad.queryForObject("SELECT count(*) FROM ad_billing_ledger",Long.class));
                    BigDecimal ledger=state.click.ad.queryForObject("SELECT sum(amount) FROM ad_click_ledger",BigDecimal.class);
                    BigDecimal charged=state.click.ad.queryForObject("SELECT sum(charged_amount) FROM ad_budget_account",BigDecimal.class);
                    assertEquals(0,ledger.compareTo(charged),"真实预算扣费与账本一致");
                }
            }
        }
    }
    @Configuration(proxyBeanMethods=false) @EnableWebSecurity
    @Import({ServletWebServerFactoryAutoConfiguration.class,DispatcherServletAutoConfiguration.class,WebMvcAutoConfiguration.class,
            JacksonAutoConfiguration.class,HttpMessageConvertersAutoConfiguration.class,
            SandboxClickDeliveryController.class,SandboxClickReceiptController.class,FixtureController.class})
    static class Web {
        @Bean AdServingGateway gateway(){return state.gateway;}
        @Bean ClickPreparationService clickPreparations(){return state.click.preparations;}
        @Bean ClickSettlementService clickSettlements(){return state.click.settlements;}
        @Bean("clickDeliveryOperationService") DeliveryOperationService clicks(){return state.click.delivery(state.gateway);}
        @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.csrf(c->c.disable()).authorizeHttpRequests(a->a.anyRequest().permitAll())
                    .addFilterBefore(new OncePerRequestFilter() {
                        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
                                throws java.io.IOException,ServletException {
                            if(!request.getRequestURI().startsWith("/api/")){chain.doFilter(request,response);return;}
                            String subject=state.subject(request.getHeader("Authorization"));
                            if(subject==null){response.sendError(401);return;}
                            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(subject,"",List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
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
        @PostMapping("/fixture/finish") void finish(){state.done.countDown();}
    }
    static class State implements AutoCloseable {
        final ClickOnlineFixture click;
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
            gateway=click.gateway("grpc".equals(System.getProperty("recsys.actions.mode")),click.preparations,click.settlements);
        }
        @Override public void close() throws Exception {click.close();}
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
    }
}
