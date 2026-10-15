package com.recsys.adserving.grpc;

import com.recsys.ad.billing.*;
import com.recsys.advertiser.AdvertiserRepository;
import com.recsys.advertiser.authz.AdvertiserAuthz;
import com.recsys.advertiser.billing.*;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.common.error.ErrorCode;
import com.recsys.platform.web.GlobalExceptionHandler;
import com.recsys.proto.billing.v1.SandboxBudgetServiceGrpc;
import com.recsys.proto.click.v1.SandboxClickServiceGrpc;
import com.recsys.proto.conversion.v1.SandboxConversionServiceGrpc;
import com.recsys.proto.impression.v1.SandboxImpressionServiceGrpc;
import com.recsys.proto.security.InternalAuthGrpcClientInterceptor;
import com.recsys.proto.security.InternalAuthGrpcServerInterceptor;
import com.recsys.recengine.ad.GrpcAdServingGateway;
import com.recsys.recengine.delivery.*;
import io.grpc.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP使用真实Controller/Client和TCP服务，区分内部身份失败与业务拒绝；资金事务由PG套件验证。 */
@Tag("money-chain")
class SandboxRpcClientErrorTest {
    private static final String SECRET = "client-error-test-only-internal-secret";
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    record Boundary(BindableService service, String caller, Object controller, Consumer<Channel> bind,
                    String route, ErrorCode unavailable, ErrorCode trustedError) {}

    static Stream<Boundary> boundaries() {
        var budgetOwner = mock(BudgetAccountService.class);
        when(budgetOwner.findCommand(1, ID)).thenThrow(new IllegalStateException("private SQL token"));
        var advertisers = mock(AdvertiserRepository.class);
        when(advertisers.advertiserExists(1)).thenReturn(true);
        var budget = new GrpcSandboxBudgetClient();
        var receiptOwner = mock(ImpressionReceiptService.class);
        when(receiptOwner.find(ID, "admin")).thenThrow(new SandboxDelivery.InvalidProof());
        var receipt = new GrpcAdServingGateway();
        var clickOwner = mock(ClickSettlementService.class);
        when(clickOwner.findReceipt(ID, "admin")).thenThrow(new SandboxDelivery.InvalidProof());
        var click = new GrpcAdServingGateway();
        var conversionOwner = mock(ConversionSettlementService.class);
        when(conversionOwner.findVisible(ID, "admin")).thenThrow(new SandboxDelivery.InvalidProof());
        when(conversionOwner.findAuthorizedOutcome(1, ID)).thenThrow(new SandboxDelivery.InvalidProof());
        var conversion = new GrpcAdServingGateway();
        var outcome = new GrpcSandboxConversionClient();
        var authz = mock(AdvertiserAuthz.class);
        when(authz.requireConversionSubmission(1)).thenReturn("admin");
        var conversionService = new SandboxConversionGrpcService(mock(ConversionPreparationService.class), conversionOwner, "engine", "advertiser");
        return Stream.of(
                new Boundary(new SandboxBudgetGrpcService(budgetOwner, "advertiser"), "advertiser",
                        new SandboxBudgetController(advertisers, budget), c -> ReflectionTestUtils.setField(budget, "stub",
                        SandboxBudgetServiceGrpc.newBlockingStub(c).withDeadlineAfter(5, TimeUnit.SECONDS)),
                        "/api/advertiser/1/sandbox-budget-commands/", ErrorCode.BUDGET_DEPENDENCY_UNAVAILABLE, ErrorCode.BUDGET_RESULT_UNKNOWN),
                new Boundary(new SandboxImpressionGrpcService(receiptOwner, "engine"), "engine",
                        new SandboxImpressionController(receipt), c -> ReflectionTestUtils.setField(receipt, "impressionStub",
                        SandboxImpressionServiceGrpc.newBlockingStub(c).withDeadlineAfter(5, TimeUnit.SECONDS)),
                        "/api/sandbox/ad-impressions/", ErrorCode.RECEIPT_503, ErrorCode.RECEIPT_403),
                new Boundary(new SandboxClickGrpcService(mock(ClickPreparationService.class), clickOwner, "engine"), "engine",
                        new SandboxClickReceiptController(click), c -> ReflectionTestUtils.setField(click, "clickStub",
                        SandboxClickServiceGrpc.newBlockingStub(c).withDeadlineAfter(5, TimeUnit.SECONDS)),
                        "/api/sandbox/click-receipts/", ErrorCode.CLICK_RECEIPT_503, ErrorCode.CLICK_RECEIPT_403),
                new Boundary(conversionService, "engine", new SandboxConversionImpressionController(conversion),
                        c -> ReflectionTestUtils.setField(conversion, "conversionStub", SandboxConversionServiceGrpc.newBlockingStub(c).withDeadlineAfter(5, TimeUnit.SECONDS)),
                        "/api/sandbox/conversion-impressions/", ErrorCode.CONVERSION_RECEIPT_503, ErrorCode.CONVERSION_RECEIPT_403),
                new Boundary(conversionService, "advertiser", new SandboxConversionController(authz, outcome),
                        c -> ReflectionTestUtils.setField(outcome, "stub", SandboxConversionServiceGrpc.newBlockingStub(c).withDeadlineAfter(5, TimeUnit.SECONDS)),
                        "/api/advertiser/1/sandbox-conversions/", ErrorCode.CONVERSION_503, ErrorCode.CONVERSION_403));
    }

    @ParameterizedTest
    @MethodSource("boundaries")
    void internalIdentityFailuresAreUnavailableWhileDomainDenialsRemainForbidden(Boundary b) throws Exception {
        var server = ServerBuilder.forPort(0).addService(ServerInterceptors.intercept(b.service(),
                new InternalAuthGrpcServerInterceptor("x-internal-token", SECRET, false))).build().start();
        var channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        var mvc = MockMvcBuilders.standaloneSetup(b.controller())
                .defaultRequest(get("/").accept(org.springframework.http.MediaType.APPLICATION_JSON))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var admin = new UsernamePasswordAuthenticationToken("admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        try {
            // 同一HTTP用户始终已授权；只改变内部服务凭据，不能误报用户权限失败。
            for (String caller : new String[]{"", "wrong-service", b.caller()}) {
                b.bind().accept(caller.isEmpty() ? channel : ClientInterceptors.intercept(channel,
                        new InternalAuthGrpcClientInterceptor("x-internal-token", caller, "SERVICE", 60, SECRET)));
                var expected = caller.equals(b.caller()) ? b.trustedError() : b.unavailable();
                mvc.perform(get(b.route() + ID).principal(admin)).andExpect(status().is(expected.httpStatus()))
                        .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))
                        .andExpect(jsonPath("$.code").value(expected.wireCode()))
                        .andExpect(jsonPath("$.message").value(expected.message()))
                        .andExpect(jsonPath("$.path").value(b.route() + ID));
            }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
