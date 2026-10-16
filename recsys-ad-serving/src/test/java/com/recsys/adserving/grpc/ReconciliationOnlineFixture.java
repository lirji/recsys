package com.recsys.adserving.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.ad.billing.*;
import com.recsys.ad.delivery.DeliveryTokenCodec;
import com.recsys.common.ad.billing.BudgetCommand;
import com.recsys.persistence.spring.SpringSqlSessions;
import com.recsys.proto.reconciliation.v1.SandboxReconciliationServiceGrpc;
import com.recsys.proto.security.*;
import io.grpc.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import java.time.*;
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
    @Override public void close() throws Exception {
        if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        if (server != null) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
}
