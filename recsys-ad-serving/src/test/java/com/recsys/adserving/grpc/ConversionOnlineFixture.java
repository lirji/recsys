package com.recsys.adserving.grpc;

import org.mybatis.spring.SqlSessionTemplate;
import com.recsys.persistence.spring.SpringSqlSessions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.ad.*;
import com.recsys.ad.billing.*;
import com.recsys.ad.delivery.DeliveryTokenCodec;
import com.recsys.common.ad.*;
import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.delivery.SandboxDelivery.*;
import com.recsys.common.query.*;
import com.recsys.recengine.ad.*;
import com.recsys.recengine.delivery.*;
import com.recsys.recengine.experiment.ExperimentService;
import com.recsys.proto.conversion.v1.SandboxConversionServiceGrpc;
import com.recsys.proto.security.*;
import io.grpc.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.mockito.Mockito.*;

/** 隔离两个owner schema，真实资金/交付事务与TCP RPC；仅候选排序和实验外部依赖用替身。 */
class ConversionOnlineFixture implements AutoCloseable {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static final String SECRET="conversion-online-fixture-internal-only";
    final DriverManagerDataSource adDs,engineDs;
    final JdbcTemplate ad,engine;
    final SqlSessionTemplate sessions;
    final AdPipeline pipeline=mock(AdPipeline.class);
    final ConversionQuoteRepository quotes;
    final ConversionSettlementService settlements;
    final PreparationRepository preparationRepository;
    final ConversionPreparationService preparations;
    final DeliveryTokenCodec tokens=new DeliveryTokenCodec(JSON,"test",Map.of("test",Base64.getEncoder().encodeToString(new byte[32])));
    final BudgetAccountService budgets;
    final DeliveryOperationRepository operations;
    final QueryUnderstandingService queries=mock(QueryUnderstandingService.class);
    final ExperimentService experiments=mock(ExperimentService.class);
    Server server;ManagedChannel channel;
    ConversionOnlineFixture(PostgreSQLContainer<?> pg) throws Exception {
        adDs=schema(pg,"ad");engineDs=schema(pg,"engine");ad=new JdbcTemplate(adDs);engine=new JdbcTemplate(engineDs);
        sessions=new SqlSessionTemplate(SpringSqlSessions.create(adDs, "classpath*:mapper/ad/addb/**/*.xml"));
        for(String name:List.of("V001__budget_account.sql","V002__cpm_quote.sql","V003__budget_reservation.sql","V004__delivery_preparation.sql",
                "V005__impression_capture.sql","V006__billing_event_delivery.sql","V007__feed_frequency.sql","V008__click_quote.sql",
                "V009__click_settlement.sql","V010__click_event_delivery.sql","V011__click_preparation.sql","V012__conversion_quote.sql",
                "V013__conversion_settlement.sql","V014__conversion_event_delivery.sql","V015__conversion_preparation.sql")) migrate(adDs,"db/ad-billing/"+name);
        migrate(engineDs,DeliveryOperationRepository.MIGRATION);migrate(engineDs,DeliveryOperationRepository.CLICK_MIGRATION);
        migrate(engineDs,DeliveryOperationRepository.CONVERSION_MIGRATION);
        ad.execute("""
                CREATE FUNCTION test_conversion_clock() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN NEW.issued_at=NEW.issued_at-interval '2 seconds';NEW.expires_at=NEW.expires_at-interval '2 seconds';RETURN NEW;END;$$;
                CREATE TRIGGER test_conversion_clock BEFORE INSERT ON ad_conversion_quote FOR EACH ROW EXECUTE FUNCTION test_conversion_clock();
                """);
        quotes=new ConversionQuoteRepository(sessions);preparationRepository=new PreparationRepository(sessions,JSON,ChargeMode.CONVERSION);
        settlements=new ConversionSettlementService(new ConversionSettlementRepository(sessions,JSON),quotes,new ReservationRepository(sessions),tokens,
                transactions(adDs),true,true);
        preparations=new ConversionPreparationService(pipeline,preparationRepository,quotes,settlements,tokens,true);
        budgets=new BudgetConfiguration().budgetAccountService(new BudgetRepository(sessions),adDs,5);
        var day=preparationRepository.now().atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate();
        budgets.setLimit(BudgetCommand.parse(Long.MAX_VALUE,UUID.randomUUID().toString(),day.toString(),"CNY","10",0,"admin"));
        when(pipeline.sandboxConversionCandidates(anyLong(),any(),anyInt(),anyString(),anyDouble())).thenAnswer(i->{
            var price=new ConversionPrice(new GspPrice(new BigDecimal("0.123456"),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE));
            var a=new SponsoredAd(Long.MAX_VALUE-1,Long.MAX_VALUE-2,Long.MAX_VALUE,0,"冻结转化正文",AdChannel.KW_EXACT,1,1,1,1,1,1,1,1,Long.MAX_VALUE-3,"CPA");
            return List.of(new BiddingService.ConversionQuotedAd(a,price));
        });
        when(queries.parse(anyString(),anyLong())).thenAnswer(i->new StructuredQuery(i.getArgument(0),i.getArgument(0),null,null,null,null));
        operations=new DeliveryOperationRepository(engine,JSON,ChargeMode.CONVERSION);operations.verifySchema();
    }
    static DriverManagerDataSource schema(PostgreSQLContainer<?> pg,String prefix) {
        String schema=prefix+UUID.randomUUID().toString().replace("-","");
        new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword())).execute("CREATE SCHEMA "+schema);
        return new DriverManagerDataSource(pg.getJdbcUrl()+(pg.getJdbcUrl().contains("?")?"&":"?")+"currentSchema="+schema,pg.getUsername(),pg.getPassword());
    }
    static void migrate(DriverManagerDataSource ds,String path) throws Exception {
        try(var in=new ClassPathResource(path).getInputStream();var c=ds.getConnection();var st=c.createStatement()) {
            byte[] bytes=in.readAllBytes();
            st.execute("SET recsys.migration_sha256='"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))+"'");
            st.execute(new String(bytes,StandardCharsets.UTF_8));
        }
    }
    static org.springframework.transaction.support.TransactionTemplate transactions(javax.sql.DataSource ds) {
        var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.support.JdbcTransactionManager(ds));
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(5);return tx;
    }
    AdServingGateway gateway(boolean remote,ConversionPreparationService prep,ConversionSettlementService money) throws Exception {
        // 广告主回调始终跨可信RPC；展示可独立选择in-process或gRPC。
        server=ServerBuilder.forPort(0).addService(ServerInterceptors.intercept(new SandboxConversionGrpcService(prep,money,"recsys-rec-engine","recsys-advertiser"),
                new InternalAuthGrpcServerInterceptor("x-internal-token",SECRET,false))).build().start();
        channel=ManagedChannelBuilder.forAddress("localhost",server.getPort()).usePlaintext().build();
        if(!remote) {
            var local=new InProcessAdServingGateway(pipeline);ReflectionTestUtils.setField(local,"conversionPreparations",provider(prep));
            ReflectionTestUtils.setField(local,"conversionSettlements",provider(money));return local;
        }
        var stub=SandboxConversionServiceGrpc.newBlockingStub(channel).withDeadlineAfter(20,TimeUnit.SECONDS)
                .withInterceptors(new InternalAuthGrpcClientInterceptor("x-internal-token","recsys-rec-engine","SERVICE",60,SECRET));
        var gateway=new GrpcAdServingGateway();ReflectionTestUtils.setField(gateway,"conversionStub",stub);return gateway;
    }
    static <T> ObjectProvider<T> provider(T bean) { ObjectProvider<T> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(bean);return provider; }
    DeliveryOperationService delivery(AdServingGateway gateway) { return new DeliveryOperationService(operations,queries,experiments,new AdProperties(),gateway); }
    @Override public void close() throws Exception {
        if(channel!=null)channel.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);
        if(server!=null)server.shutdownNow().awaitTermination(5,TimeUnit.SECONDS);
    }
}
