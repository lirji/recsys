package com.recsys.behavior.exposure;

import org.mybatis.spring.SqlSessionTemplate;
import com.recsys.persistence.spring.SpringSqlSessions;
import com.recsys.ad.*;
import com.recsys.ad.billing.*;
import com.recsys.ad.delivery.DeliveryTokenCodec;
import com.recsys.common.ad.*;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.delivery.SandboxDelivery.*;
import com.recsys.common.query.*;
import com.recsys.common.exposure.NaturalQualification;
import com.recsys.recengine.ad.*;
import com.recsys.recengine.delivery.SandboxImpressionController;
import com.recsys.recengine.experiment.ExperimentService;
import com.recsys.recengine.feed.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.testcontainers.containers.PostgreSQLContainer;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.recsys.behavior.exposure.NaturalExposureBrowserAcceptanceTest.JSON;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** S10 隔离浏览器宿主：三 owner 三 schema，真实冻结/预占/捕获/自然事实，候选只读 SQL 种子。 */
@EnabledIfSystemProperty(named="recsys.feed.browser.fixture",matches="true")
class FeedExposureBrowserAcceptanceTest {
    static FeedState state;
    @Test void serveUntilBrowserFinishesAndReconcileFacts() throws Exception {
        try(var pg=new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start(); state=new FeedState(pg); NaturalExposureBrowserAcceptanceTest.state=state;
            try(var context=(ServletWebServerApplicationContext)new SpringApplication(Web.class).run(
                    "--spring.config.location=optional:classpath:/feed-browser-fixture-not-present.yml",
                    "--spring.cloud.nacos.config.import-check.enabled=false","--server.address=127.0.0.1","--server.port=0","--server.tomcat.threads.max=8",
                    "--recsys.feed.delivery-enabled=true","--recsys.natural.delivery-enabled=true","--recsys.natural.receipts-enabled=true",
                    "--recsys.ad.billing.sandbox-enabled=true","--recsys.ad.billing.receipts-enabled=true")) {
                var output=Path.of(System.getProperty("recsys.feed.browser.fixture-info","target/feed-browser-fixture.json"));
                Files.createDirectories(output.toAbsolutePath().getParent());
                Files.writeString(output,JSON.writeValueAsString(Map.of("url","http://127.0.0.1:"+context.getWebServer().getPort(),
                        "token",state.token("browser-admin"),"otherToken",state.token("other-admin"))));
                assertTrue(state.done.await(10,TimeUnit.MINUTES),"feed 浏览器未完成");
                assertTrue(state.adDb.queryForObject("SELECT count(*) FROM ad_billing_ledger",Long.class)>0);
                assertTrue(state.behavior.queryForObject("SELECT count(*) FROM natural_impression_fact",Long.class)>0);
                assertEquals(0L,state.adDb.queryForObject("SELECT count(*) FROM ad_billing_ledger l LEFT JOIN ad_billing_outbox o ON o.event_id=l.ledger_id WHERE o.event_id IS NULL",Long.class));
                assertEquals(0L,state.behavior.queryForObject("SELECT count(*) FROM natural_impression_fact f LEFT JOIN natural_impression_outbox o ON o.event_id=f.qualification_id WHERE o.event_id IS NULL",Long.class));
                assertEquals(0L,state.adDb.queryForObject("SELECT count(*) FROM ad_cpm_quote WHERE ad_id IN (101,201)",Long.class),"混排剔除不能生成报价或预占");
                assertEquals(0L,state.adDb.queryForObject("SELECT count(*) FROM ad_budget_account a WHERE a.charged_amount<>(SELECT COALESCE(sum(l.amount),0) FROM ad_billing_ledger l WHERE l.advertiser_id=a.advertiser_id AND l.budget_date=a.budget_date)",Long.class));
                verify(state.recommender,never()).recommend(any()); verify(state.pipeline,never()).run(anyLong(),any(),anyInt(),anyString(),anyString(),anyDouble());
            }
        }
    }
    @Configuration(proxyBeanMethods=false)
    @Import({NaturalExposureBrowserAcceptanceTest.Web.class,FeedDeliveryController.class,SandboxImpressionController.class,Probe.class})
    static class Web {
        @Bean FeedDeliveryService feed(){return state.feed;}
        @Bean AdServingGateway gateway(){return state.gateway;}
    }
    enum Mode { NORMAL, CANDIDATE_DOWN, PREPARE_ACK_LOST, BUDGET_DENIED }
    @RestController static class Probe {
        /** 只在本次独占 loopback fixture 中注入故障，不修改产品入口或共享环境。 */
        @PostMapping("/fixture/feed-mode/{mode}") void mode(@PathVariable Mode mode) {state.mode=mode;state.lost.set(false);}
        record Facts(long qualified,long ledgers,String charged,List<Integer> adSlots,List<Integer> naturalSlots,List<String> children) {}
        /** 从真实 owner 库读最终结果，不从前端回显推导资金成功。 */
        @GetMapping("/fixture/feed/{id}") Facts facts(@PathVariable UUID id) {
            long held=state.adDb.queryForObject("SELECT count(*) FROM ad_budget_reservation r JOIN ad_cpm_quote q ON q.quote_id=r.quote_id WHERE q.delivery_id=? AND r.decision='HELD'",Long.class,id);
            var adSlots=state.adDb.queryForList("SELECT slot FROM ad_billing_ledger WHERE operation_id=? ORDER BY slot",Integer.class,id);
            var natural=state.behavior.queryForList("SELECT (g.context_json::jsonb->>'slot')::int FROM natural_impression_fact f JOIN natural_qualification_guard g USING(qualification_id) WHERE g.context_json::jsonb->>'operationId'=? ORDER BY 1",Integer.class,id.toString());
            var children=state.adDb.queryForList("SELECT child_id::text FROM ad_delivery_preparation WHERE operation_id=? ORDER BY child_id",String.class,id);
            String charged=state.adDb.queryForObject("SELECT COALESCE(sum(amount),0)::numeric(20,6)::text FROM ad_billing_ledger WHERE operation_id=?",String.class,id);
            return new Facts(held,adSlots.size(),charged,adSlots,natural,children);
        }
    }
    static class FeedState extends NaturalExposureBrowserAcceptanceTest.State {
        final JdbcTemplate adDb;
        final AdPipeline pipeline=mock(AdPipeline.class);
        final AdServingGateway gateway;
        final FeedDeliveryService feed;
        volatile Mode mode=Mode.NORMAL;
        final AtomicBoolean lost=new AtomicBoolean();
        FeedState(PostgreSQLContainer<?> pg) throws Exception {
            super(pg);
            new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword())).execute("CREATE SCHEMA browser_feed_ad");
            var ds=new DriverManagerDataSource(pg.getJdbcUrl()+"&currentSchema=browser_feed_ad",pg.getUsername(),pg.getPassword()); adDb=new JdbcTemplate(ds);
            for(String path:List.of(BudgetRepository.MIGRATION,CpmQuoteRepository.MIGRATION,ReservationRepository.MIGRATION,PreparationRepository.MIGRATION,
                    ImpressionReceiptRepository.MIGRATION,"db/ad-billing/V006__billing_event_delivery.sql",FeedCandidateRepository.MIGRATION)) {
                try(var in=new ClassPathResource(path).getInputStream()) {
                    migrate(ds,path,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));
                }
            }
            migrate(engineDs,FeedDeliveryRepository.MIGRATION,FeedDeliveryRepository.migrationSha256());
            adDb.execute("CREATE TABLE browser_feed_seed(ad_id bigint PRIMARY KEY,item_id bigint NOT NULL,advertiser_id bigint NOT NULL,title text NOT NULL,position integer NOT NULL,mode text NOT NULL)");
            adDb.execute("COMMENT ON TABLE browser_feed_seed IS 'feed 浏览器隔离广告种子，非产品表'");
            for(var field:Map.of("ad_id","广告身份","item_id","关联内容身份","advertiser_id","隔离测试账户","title","冻结广告正文","position","候选顺序","mode","故障用例种子组").entrySet()) {
                adDb.execute("COMMENT ON COLUMN browser_feed_seed."+field.getKey()+" IS '"+field.getValue()+"'");
            }
            for(int group=1;group<=2;group++) {
                adDb.update("INSERT INTO browser_feed_seed VALUES (?,9007199254740993,?,'action · 重复候选不能投放',1,?),(?,9007199254740996,?,'action · 企业协作方案',2,?),(?,9007199254740997,?,'action · 数据服务方案',3,?)",
                        group*100+1,group,group==1?"NORMAL":"BUDGET_DENIED",group*100+2,group,group==1?"NORMAL":"BUDGET_DENIED",group*100+3,group,group==1?"NORMAL":"BUDGET_DENIED");
            }
            var budget=new BudgetConfiguration().budgetAccountService(new BudgetRepository(new SqlSessionTemplate(SpringSqlSessions.create(adDb.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml"))),ds,5);
            var reservations=new ReservationRepository(new SqlSessionTemplate(SpringSqlSessions.create(adDb.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml")));var quotes=new CpmQuoteRepository(new SqlSessionTemplate(SpringSqlSessions.create(adDb.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml")));var preparations=new PreparationRepository(new SqlSessionTemplate(SpringSqlSessions.create(adDb.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml")),JSON);
            var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setTimeout(5);
            for(long advertiser:List.of(1L,2L)) budget.setLimit(BudgetCommand.parse(advertiser,UUID.randomUUID().toString(),reservations.databaseNow().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString(),"CNY",advertiser==1?"100":"0",0,"browser-admin"));
            var reservationService=new ReservationService(reservations,quotes,tx,true);
            var adTokens=new DeliveryTokenCodec(JSON,"browser",Map.of("browser",Base64.getEncoder().encodeToString(key)));
            when(pipeline.sandboxCandidates(anyLong(),any(),anyInt(),anyString(),anyDouble())).thenAnswer(call->
                    adDb.query("SELECT * FROM browser_feed_seed WHERE mode=? ORDER BY position",(rs,n)->{
                        var ad=new SponsoredAd(rs.getLong("ad_id"),rs.getLong("item_id"),rs.getLong("advertiser_id"),0,rs.getString("title"),AdChannel.KW_EXACT,1,1,1,1,1,1,1,rs.getInt("position"),0,"CPM");
                        return new BiddingService.QuotedAd(ad,new CpmPrice(new BigDecimal("0.123456"),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE));
                    },mode==Mode.BUDGET_DENIED?"BUDGET_DENIED":"NORMAL"));
            var preparation=new SandboxPreparationService(pipeline,preparations,quotes,reservationService,adTokens);
            var frequency=new FeedCandidateRepository(adDb);frequency.verifySchema();
            var candidates=new FeedCandidateService(preparation,frequency,true,1,8);
            var capture=new ImpressionReceiptService(new ImpressionReceiptRepository(new SqlSessionTemplate(SpringSqlSessions.create(adDb.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml")),JSON),preparations,reservations,quotes,adTokens,tx,true);
            var local=new InProcessAdServingGateway(pipeline) {
                @Override public List<Candidate> sandboxFeedCandidates(Query query) {
                    if(mode==Mode.CANDIDATE_DOWN) throw new IllegalStateException("fixture candidate down");
                    return candidates.candidates(query);
                }
                @Override public Prepared prepareSandbox(Prepare command) {
                    var result=preparation.prepare(command);
                    if(mode==Mode.PREPARE_ACK_LOST && lost.compareAndSet(false,true)) throw new IllegalStateException("fixture ACK lost after real commit");
                    return result;
                }
                @Override public Optional<Prepared> findSandboxPreparation(UUID id,String subject) {return preparation.find(id,subject);}
                @Override public ImpressionReceipt.Result acceptSandboxImpression(ImpressionReceipt.Command c) {return capture.accept(c);}
                @Override public Optional<ImpressionReceipt.Result> findSandboxImpression(UUID id,String subject) {return capture.find(id,subject);}
            };
            gateway=local;
            var properties=new AdProperties();properties.getAdLoad().setSlots(List.of(2,4));
            var queries=mock(QueryUnderstandingService.class);when(queries.parse(anyString(),anyLong())).thenAnswer(c->new StructuredQuery(c.getArgument(0),c.getArgument(0),null,null,null,null));
            var repository=new FeedDeliveryRepository(engine,JSON);repository.verifySchema();
            feed=new FeedDeliveryService(repository,recommender,content,queries,mock(ExperimentService.class),gateway,properties,tokens,NaturalQualification.TEXT_POLICY_V1,true);
        }
    }
}
