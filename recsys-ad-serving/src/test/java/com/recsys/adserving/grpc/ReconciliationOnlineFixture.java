package com.recsys.adserving.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.ad.billing.*;
import com.recsys.ad.delivery.DeliveryTokenCodec;
import com.recsys.common.ad.billing.BudgetCommand;
import com.recsys.common.ad.*;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.persistence.spring.SpringSqlSessions;
import com.recsys.proto.reconciliation.v1.SandboxReconciliationServiceGrpc;
import com.recsys.proto.security.*;
import io.grpc.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 独占schema装载三族共享账户和全部迁移，用真实TCP连接对账owner，无业务服务替身。 */
class ReconciliationOnlineFixture implements AutoCloseable {
    static final String SECRET = "reconciliation-isolated-test-only-secret";
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    final DriverManagerDataSource ds;
    final JdbcTemplate jdbc;
    final SqlSessionTemplate sessions;
    final BudgetAccountService budgets;
    final ReconciliationService snapshots;
    final ReconciliationTaskService tasks;
    final BillingRecoveryService recovery;
    final LocalDate day;
    Server server;
    ManagedChannel channel;

    ReconciliationOnlineFixture(PostgreSQLContainer<?> pg) throws Exception {
        ds = ConversionOnlineFixture.schema(pg, "reconciliation"); jdbc = new JdbcTemplate(ds);
        for (var migration : List.of("V001__budget_account.sql", "V002__cpm_quote.sql", "V003__budget_reservation.sql",
                "V004__delivery_preparation.sql", "V005__impression_capture.sql", "V006__billing_event_delivery.sql", "V007__feed_frequency.sql",
                "V008__click_quote.sql", "V009__click_settlement.sql", "V010__click_event_delivery.sql", "V011__click_preparation.sql",
                "V012__conversion_quote.sql", "V013__conversion_settlement.sql", "V014__conversion_event_delivery.sql", "V015__conversion_preparation.sql",
                "V016__reconciliation_indexes.sql", "V017__reconciliation_tasks.sql", "V018__billing_recovery.sql")) {
            ConversionOnlineFixture.migrate(ds, "db/ad-billing/" + migration);
        }
        sessions = new SqlSessionTemplate(SpringSqlSessions.create(ds, "classpath*:mapper/ad/addb/**/*.xml"));
        var reservations = new ReservationRepository(sessions); var cpmQuotes = new CpmQuoteRepository(sessions);
        var tokens = new DeliveryTokenCodec(JSON, "test", Map.of("test", Base64.getEncoder().encodeToString(new byte[32])));
        var impressions = new ReservationService(reservations, cpmQuotes, ConversionOnlineFixture.transactions(ds), false);
        var clicks = new ClickSettlementService(new ClickSettlementRepository(sessions, JSON), new ClickQuoteRepository(sessions), reservations, tokens,
                ConversionOnlineFixture.transactions(ds), false, false, new ClickSettlementService.FraudPolicy(true, 20));
        var conversions = new ConversionSettlementService(new ConversionSettlementRepository(sessions, JSON), new ConversionQuoteRepository(sessions), reservations,
                tokens, ConversionOnlineFixture.transactions(ds), false, false);
        var reports = new ReconciliationRepository(sessions); reports.verifySchema();
        snapshots = new ReconciliationConfiguration().reconciliationService(reports, ds);
        var taskRepository = new ReconciliationTaskRepository(sessions, JSON); taskRepository.verifySchema();
        tasks = new ReconciliationTaskService(taskRepository, snapshots, ConversionOnlineFixture.transactions(ds));
        var recoveryRepository = new BillingRecoveryRepository(sessions, JSON); recoveryRepository.verifySchema();
        recovery = new BillingRecoveryService(recoveryRepository, impressions, clicks, conversions, ConversionOnlineFixture.transactions(ds));
        budgets = new BudgetConfiguration().budgetAccountService(new BudgetRepository(sessions), ds, 5);
        day = reservations.databaseNow().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        budgets.setLimit(BudgetCommand.parse(Long.MAX_VALUE, UUID.randomUUID().toString(), day.toString(), "CNY", "123.123456", 0, "operator"));
    }
    void start(boolean management) throws Exception {
        server = ServerBuilder.forPort(0).addService(ServerInterceptors.intercept(new SandboxReconciliationGrpcService(snapshots,
                ConversionOnlineFixture.provider(management ? tasks : null), ConversionOnlineFixture.provider(management ? recovery : null),
                "recsys-advertiser", "recsys-offline"), new InternalAuthGrpcServerInterceptor("x-internal-token", SECRET, false))).build().start();
        channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
    }
    SandboxReconciliationServiceGrpc.SandboxReconciliationServiceBlockingStub stub(String caller) {
        var stub = SandboxReconciliationServiceGrpc.newBlockingStub(channel).withDeadlineAfter(20, TimeUnit.SECONDS);
        return caller == null ? stub : stub.withInterceptors(new InternalAuthGrpcClientInterceptor("x-internal-token", caller, "SERVICE", 60, SECRET));
    }

    /** 用原收费用例准备三族同账户事实；报价时间只在独占schema新INSERT时前移，保持五分钟协议。 */
    void seedThreeFamilies() {
        jdbc.execute("CREATE FUNCTION report_seed_quote_time() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN NEW.issued_at=NEW.issued_at-interval '2 seconds';NEW.expires_at=NEW.expires_at-interval '2 seconds';RETURN NEW;END;$$");
        for (var table : List.of("ad_cpm_quote", "ad_click_quote", "ad_conversion_quote")) {
            jdbc.execute("CREATE TRIGGER report_seed_quote_time BEFORE INSERT ON " + table + " FOR EACH ROW EXECUTE FUNCTION report_seed_quote_time()");
        }
        var tokens = new DeliveryTokenCodec(JSON, "test", Map.of("test", Base64.getEncoder().encodeToString(new byte[32])));
        var reservations = new ReservationRepository(sessions); var quotes = new CpmQuoteRepository(sessions);
        var preparations = new PreparationRepository(sessions, JSON);
        var prepare = new SandboxPreparationService(org.mockito.Mockito.mock(com.recsys.ad.AdPipeline.class), preparations, quotes,
                new ReservationService(reservations, quotes, ConversionOnlineFixture.transactions(ds), true), tokens);
        var receipt = new ImpressionReceiptService(new ImpressionReceiptRepository(sessions, JSON), preparations, reservations, quotes, tokens,
                ConversionOnlineFixture.transactions(ds), true);
        var id = UUID.randomUUID(); var operation = UUID.randomUUID();
        var ad = new SponsoredAd(10, 20, Long.MAX_VALUE, 0, "账户日报表种子", AdChannel.KW_EXACT, 1, 1, 1, 1, 1, 1, 1, 1, 88, "CPM");
        var cpm = new CpmPrice(new BigDecimal("0.100001"), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE);
        quotes.freeze(id, operation, Long.MAX_VALUE, ad.adId(), 1, cpm);
        var offer = new SandboxDelivery.Offer(operation, "admin", 1, id, ad, cpm, "test", reservations.databaseNow().plusSeconds(300));
        var prepared = prepare.prepare(new SandboxDelivery.Prepare(operation, "admin", 1, id, 1, tokens.sign("offer", offer)));
        receipt.accept(new ImpressionReceipt.Command(UUID.randomUUID(), "admin", prepared.qualificationToken(), 1000, 5000, true));
        var clickQuotes = new ClickQuoteRepository(sessions);
        var clicks = new ClickSettlementService(new ClickSettlementRepository(sessions, JSON), clickQuotes, reservations, tokens,
                ConversionOnlineFixture.transactions(ds), true, true, new ClickSettlementService.FraudPolicy(true, 20));
        id = UUID.randomUUID(); var amount = new BigDecimal("0.200002");
        clickQuotes.freezeBefore(new ClickQuoteRepository.Draft(id, UUID.randomUUID(), "admin", 1, Long.MAX_VALUE, 11, 3, 4, 1, "点击种子",
                new ClickPrice(BidType.CPC, new GspPrice(amount, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, amount))), Instant.now().plusSeconds(30)).orElseThrow();
        var token = clicks.prepare(id, "admin").qualificationToken();
        clicks.accept(new ClickSettlement.Command(UUID.randomUUID(), "admin", token, ClickSettlement.Kind.VISIBLE, 1000, 5000, true));
        clicks.accept(new ClickSettlement.Command(UUID.randomUUID(), "admin", token, ClickSettlement.Kind.CLICK, 0, 0, false));
        var conversionQuotes = new ConversionQuoteRepository(sessions);
        var conversions = new ConversionSettlementService(new ConversionSettlementRepository(sessions, JSON), conversionQuotes, reservations, tokens,
                ConversionOnlineFixture.transactions(ds), true, true);
        id = UUID.randomUUID(); amount = new BigDecimal("0.300003");
        var quote = conversionQuotes.freezeBefore(new ConversionQuoteRepository.Draft(id, UUID.randomUUID(), "admin", 1, Long.MAX_VALUE, 12, 3, 4, 1, "转化种子",
                new ConversionPrice(new GspPrice(amount, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, amount))), Instant.now().plusSeconds(30)).orElseThrow();
        token = conversions.prepare(id, "admin").qualificationToken();
        conversions.acceptVisible(new ConversionSettlement.VisibleCommand(UUID.randomUUID(), "admin", token, 1000, 5000, true));
        conversions.acceptAuthorizedConversion(new ConversionSettlement.ConversionCommand(Long.MAX_VALUE, UUID.randomUUID(), "advertiser-actor", token, quote.issuedAt().plusSeconds(1)));
    }

    /** 复用实际投影用例，供只读报表观察同一资金事实从落后到追平。 */
    void projectAll() {
        var cpm = new BillingProjectionService(new BillingProjectionRepository(sessions), new BillingEventCodec(JSON), ConversionOnlineFixture.transactions(ds));
        var click = new ClickProjectionService(new ClickProjectionRepository(sessions), new ClickEventCodec(JSON), ConversionOnlineFixture.transactions(ds));
        var conversion = new ConversionProjectionService(new ConversionProjectionRepository(sessions), new ConversionEventCodec(JSON), ConversionOnlineFixture.transactions(ds));
        for (var table : List.of("ad_billing_outbox", "ad_click_outbox", "ad_conversion_outbox")) {
            jdbc.query("SELECT partition_key,payload_json FROM " + table, rs -> {
                var key = rs.getString(1); var payload = rs.getString(2);
                switch (table) {
                    case "ad_billing_outbox" -> cpm.apply(key, payload);
                    case "ad_click_outbox" -> click.apply(key, payload);
                    default -> conversion.apply(key, payload);
                }
            });
        }
    }
    @Override public void close() throws Exception {
        if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        if (server != null) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
}
