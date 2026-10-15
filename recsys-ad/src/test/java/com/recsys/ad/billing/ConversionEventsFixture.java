package com.recsys.ad.billing;

import org.mybatis.spring.SqlSessionTemplate;
import com.recsys.persistence.spring.SpringSqlSessions;
import com.recsys.common.ad.BidType;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.ConversionSettlement.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** 每测试独立schema；保留CPM/CPC fixture以验证共享账户和三种投影共存，CPA事实由真实用例产生。 */
class ConversionEventsFixture {
    static final com.fasterxml.jackson.databind.ObjectMapper JSON=BillingEventsFixture.JSON;
    static final String MIGRATION="db/ad-billing/V014__conversion_event_delivery.sql";
    final ClickEventsFixture clicks;
    final BillingEventsFixture cpm;
    final DriverManagerDataSource ds;
    final JdbcTemplate jdbc;
    final BillingOutboxRepository outbox;
    final ConversionEventCodec codec=new ConversionEventCodec(JSON);
    final ConversionProjectionService projection;
    final ConversionQuoteRepository quotes;
    final ConversionSettlementService settlement;
    ConversionEventsFixture(PostgreSQLContainer<?> pg) throws Exception {
        clicks=new ClickEventsFixture(pg);cpm=clicks.cpm;ds=cpm.ds;jdbc=cpm.jdbc;
        for(String path:List.of(PreparationRepository.CLICK_MIGRATION,ConversionQuoteRepository.MIGRATION,ConversionSettlementRepository.MIGRATION,MIGRATION)) {
            try(var in=new ClassPathResource(path).getInputStream();var connection=ds.getConnection();var st=connection.createStatement()) {
                st.execute("SET recsys.migration_sha256='"+BudgetRepository.migrationSha256(path)+"'");
                st.execute(new String(in.readAllBytes(),StandardCharsets.UTF_8));
            }
        }
        // 仅该独占schema的新报价INSERT时钟回拨两秒，保证观测合法，不修改产品不可变历史。
        jdbc.execute("""
                CREATE FUNCTION test_conversion_issue_time() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN NEW.issued_at=NEW.issued_at-interval '2 seconds'; NEW.expires_at=NEW.expires_at-interval '2 seconds'; RETURN NEW; END; $$;
                CREATE TRIGGER test_conversion_issue_time BEFORE INSERT ON ad_conversion_quote FOR EACH ROW EXECUTE FUNCTION test_conversion_issue_time();
                """);
        quotes=new ConversionQuoteRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml")));
        settlement=new ConversionSettlementService(new ConversionSettlementRepository(new SqlSessionTemplate(SpringSqlSessions.create(jdbc.getDataSource(), "classpath*:mapper/ad/addb/**/*.xml")),JSON),quotes,cpm.reservations,cpm.tokens,
                BudgetConfiguration.transactions(ds,5),true,true);
        outbox=new BillingOutboxRepository(cpm.sessions,BillingOutboxRepository.EventFamily.CONVERSION);outbox.verifySchema();
        projection=new ConversionProjectionService(new ConversionProjectionRepository(jdbc),codec,BudgetConfiguration.transactions(ds,5));
    }
    UUID capture(long advertiser,String amount) { return capture(advertiser,amount,UUID.randomUUID()); }
    UUID capture(long advertiser,String amount,UUID businessEvent) {
        if(cpm.budgets.findAccount(advertiser,cpm.today()).isEmpty()) cpm.budgets.setLimit(BudgetCommand.parse(advertiser,UUID.randomUUID().toString(),
                cpm.today().toString(),"CNY","100",0,"admin"));
        UUID id=UUID.randomUUID(); var money=new BigDecimal(amount);
        var q=quotes.freezeBefore(new ConversionQuoteRepository.Draft(id,UUID.randomUUID(),"admin",advertiser,advertiser,2,3,9007199254740993L,1,"CPA正文",
                new ConversionPrice(new GspPrice(money,BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,money))),Instant.now().plusSeconds(30)).orElseThrow();
        var token=settlement.prepare(id,"admin").qualificationToken();
        settlement.acceptVisible(new VisibleCommand(UUID.randomUUID(),"admin",token,1000,5000,true));
        return settlement.acceptAuthorizedConversion(new ConversionCommand(advertiser,businessEvent,"advertiser-actor",token,q.issuedAt().plusSeconds(1))).settlement().ledgerId();
    }
    BillingOutboxRepository.Pending event(UUID id) {
        return jdbc.queryForObject("SELECT * FROM ad_conversion_outbox WHERE event_id=?",(rs,n)->new BillingOutboxRepository.Pending(
                id,rs.getString("destination"),rs.getString("partition_key"),rs.getString("payload_json"),rs.getLong("fence"),rs.getInt("attempts")),id);
    }
    void due(UUID id) { jdbc.update("UPDATE ad_conversion_outbox SET available_at=clock_timestamp()-interval '1 second' WHERE event_id=?",id); }
    String state(UUID id) { return jdbc.queryForObject("SELECT delivery_state FROM ad_conversion_outbox WHERE event_id=?",String.class,id); }
    long count() { return jdbc.queryForObject("SELECT count(*) FROM ad_conversion_projection_fact",Long.class); }
    BigDecimal total() { return jdbc.queryForObject("SELECT coalesce(sum(charged_amount),0) FROM ad_conversion_projection_daily",BigDecimal.class); }
}
