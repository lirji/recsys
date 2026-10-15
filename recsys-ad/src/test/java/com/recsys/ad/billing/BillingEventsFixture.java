package com.recsys.ad.billing;

import com.fasterxml.jackson.databind.*;
import com.recsys.ad.AdPipeline;
import com.recsys.ad.delivery.DeliveryTokenCodec;
import com.recsys.common.ad.*;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.ImpressionReceipt.*;
import com.recsys.common.ad.delivery.SandboxDelivery.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.mock;

/** 每个测试独立 schema，完整调用预算/预占/回执产生事实，不向共享环境灌数。 */
class BillingEventsFixture {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final DriverManagerDataSource ds;
    final JdbcTemplate jdbc;
    final BillingOutboxRepository outbox;
    final BillingEventCodec codec=new BillingEventCodec(JSON);
    final BillingProjectionService projection;
    final BudgetAccountService budgets;
    final CpmQuoteRepository quotes;
    final ReservationRepository reservations;
    final SandboxPreparationService prepare;
    final ImpressionReceiptService receipt;
    final DeliveryTokenCodec tokens;
    BillingEventsFixture(PostgreSQLContainer<?> pg) throws Exception {
        String schema="billing_"+UUID.randomUUID().toString().replace("-","");
        new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword())).execute("CREATE SCHEMA "+schema);
        ds=new DriverManagerDataSource(pg.getJdbcUrl()+"&currentSchema="+schema,pg.getUsername(),pg.getPassword());
        jdbc=new JdbcTemplate(ds);
        for(String path:List.of(BudgetRepository.MIGRATION,CpmQuoteRepository.MIGRATION,ReservationRepository.MIGRATION,
                PreparationRepository.MIGRATION,ImpressionReceiptRepository.MIGRATION,BillingOutboxRepository.MIGRATION)) {
            try(var c=ds.getConnection();var statement=c.createStatement()) {
                statement.execute("SET recsys.migration_sha256='"+BudgetRepository.migrationSha256(path)+"'");
                ScriptUtils.executeSqlScript(c,new ClassPathResource(path));
            }
        }
        budgets=new BudgetAccountService(new BudgetRepository(jdbc),BudgetConfiguration.transactions(ds,5));
        quotes=new CpmQuoteRepository(jdbc);reservations=new ReservationRepository(jdbc);
        tokens=new DeliveryTokenCodec(JSON,"test",Map.of("test",Base64.getEncoder().encodeToString(new byte[32])));
        var preparations=new PreparationRepository(jdbc,JSON);
        prepare=new SandboxPreparationService(mock(AdPipeline.class),preparations,quotes,
                new ReservationService(reservations,quotes,BudgetConfiguration.transactions(ds,5),true),tokens);
        receipt=new ImpressionReceiptService(new ImpressionReceiptRepository(jdbc,JSON),preparations,reservations,quotes,tokens,
                BudgetConfiguration.transactions(ds,5),true);
        outbox=new BillingOutboxRepository(jdbc);outbox.verifySchema();
        projection=new BillingProjectionService(new BillingProjectionRepository(jdbc),codec,BudgetConfiguration.transactions(ds,5));
    }
    LocalDate today(){return reservations.databaseNow().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();}
    UUID capture(long advertiser,String amount) {
        if(budgets.findAccount(advertiser,today()).isEmpty())budgets.setLimit(
                BudgetCommand.parse(advertiser,UUID.randomUUID().toString(),today().toString(),"CNY","100",0,"admin"));
        UUID id=UUID.randomUUID(),operation=UUID.randomUUID();
        var ad=new SponsoredAd(10,20,advertiser,0,"title",AdChannel.KW_EXACT,1,1,1,1,1,1,1,1,88,"CPM");
        var price=new CpmPrice(new BigDecimal(amount),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE);
        quotes.freeze(id,operation,advertiser,ad.adId(),1,price);
        jdbc.update("UPDATE ad_cpm_quote SET issued_at=issued_at-interval '2 seconds',expires_at=expires_at-interval '2 seconds' WHERE quote_id=?",id);
        var offer=new Offer(operation,"admin",1,id,ad,price,"test",reservations.databaseNow().plusSeconds(300));
        var prepared=prepare.prepare(new Prepare(operation,"admin",1,id,1,tokens.sign("offer",offer)));
        return receipt.accept(new Command(UUID.randomUUID(),"admin",prepared.qualificationToken(),1000,5000,true)).ledgerId();
    }
    BillingOutboxRepository.Pending event(UUID id) {
        return jdbc.queryForObject("SELECT * FROM ad_billing_outbox WHERE event_id=?",(rs,n)->new BillingOutboxRepository.Pending(
                id,rs.getString("destination"),rs.getString("partition_key"),rs.getString("payload_json"),rs.getLong("fence"),rs.getInt("attempts")),id);
    }
    void due(UUID id){jdbc.update("UPDATE ad_billing_outbox SET available_at=clock_timestamp()-interval '1 second' WHERE event_id=?",id);}
    String state(UUID id){return jdbc.queryForObject("SELECT delivery_state FROM ad_billing_outbox WHERE event_id=?",String.class,id);}
    long count(){return jdbc.queryForObject("SELECT count(*) FROM ad_billing_projection_fact",Long.class);}
    BigDecimal total(){return jdbc.queryForObject("SELECT coalesce(sum(charged_amount),0) FROM ad_billing_projection_daily",BigDecimal.class);}
}
