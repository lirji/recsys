package com.recsys.adserving.grpc;

import com.lrj.authz.protocol.*;
import com.recsys.advertiser.authz.AdvertiserAuthz;
import com.recsys.advertiser.billing.*;
import com.recsys.common.ad.billing.BillingReconciliation;
import com.recsys.common.error.ErrorCode;
import com.recsys.platform.web.PublicHttpException;
import io.grpc.Status;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.recsys.proto.reconciliation.v1.SandboxReconciliationServiceGrpc;
import com.recsys.proto.reconciliation.v1.ReconciliationSnapshot;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实HTTP控制器/TCP/PG验证金额来源和严格view；权限SDK替身不冒充真实SpiceDB。 */
@Tag("money-chain") @Testcontainers
class ReconciliationHttpPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    ReconciliationOnlineFixture f;
    final AuthzEngine engine = mock(AuthzEngine.class);
    final UsernamePasswordAuthenticationToken actor = new UsernamePasswordAuthenticationToken("viewer", "unused", List.of(new SimpleGrantedAuthority("ROLE_ADVERTISER")));
    @BeforeEach void setup() throws Exception { f = new ReconciliationOnlineFixture(PG); f.start(true); SecurityContextHolder.getContext().setAuthentication(actor); }
    @AfterEach void close() throws Exception { SecurityContextHolder.clearContext(); f.close(); }
    String path() { return "/api/advertiser/" + Long.MAX_VALUE + "/sandbox-reconciliation/" + f.day; }
    GrpcSandboxReconciliationClient client() {
        var client = new GrpcSandboxReconciliationClient(); ReflectionTestUtils.setField(client, "stub", f.stub("recsys-advertiser")); return client;
    }
    MockMvc mvc(String mode, GrpcSandboxReconciliationClient client) {
        return MockMvcBuilders.standaloneSetup(new SandboxReconciliationController(new AdvertiserAuthz(mode, ConversionOnlineFixture.provider(engine)), client)).build();
    }
    @Test void sharedThreeFamilyMoneyAndProjectionRemainDistinctAndReadOnly() throws Exception {
        f.seedThreeFamilies(); when(engine.check(any(), anyString(), any(), any())).thenReturn(true);
        var account = f.budgets.findAccount(Long.MAX_VALUE, f.day).orElseThrow(); var mvc = mvc("disabled", client());
        mvc.perform(get(path()).principal(actor)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value(1)).andExpect(jsonPath("$.scope.advertiserId").value(Long.toString(Long.MAX_VALUE)))
                .andExpect(jsonPath("$.account.chargedAmount").value("0.600006")).andExpect(jsonPath("$.account.availableAmount").value("122.523450"))
                .andExpect(jsonPath("$.account.version").isString()).andExpect(jsonPath("$.fundsStatus").value("BALANCED"))
                .andExpect(jsonPath("$.families[0].ledgerCount").value("1")).andExpect(jsonPath("$.families[1].ledgerAmount").value("0.200002"))
                .andExpect(jsonPath("$.families[2].projectionStatus").value("LAGGING")).andExpect(jsonPath("$.families[2].lastAppliedAt").isEmpty());
        assertEquals(account, f.budgets.findAccount(Long.MAX_VALUE, f.day).orElseThrow());
        assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM ad_billing_recovery_command", Integer.class));
        f.projectAll();
        mvc.perform(get(path()).principal(actor)).andExpect(status().isOk()).andExpect(jsonPath("$.account.chargedAmount").value("0.600006"))
                .andExpect(jsonPath("$.families[2].factAmount").value("0.300003")).andExpect(jsonPath("$.families[2].projectionStatus").value("CAUGHT_UP"))
                .andExpect(jsonPath("$.families[2].lastAppliedAt").isString());
        assertEquals(account, f.budgets.findAccount(Long.MAX_VALUE, f.day).orElseThrow());
        verify(engine, times(2)).check(eq(SubjectRef.user("viewer")), eq("view"), eq(ResourceRef.of("advertiser", Long.toString(Long.MAX_VALUE))), eq(Consistency.fullyConsistent()));
    }
    @ParameterizedTest @ValueSource(strings = {"disabled", "shadow", "enforce"})
    void strictViewRejectsUnrelatedAdminRevocationAnonymousAndDependencyFailureBeforeRpc(String mode) throws Exception {
        var owner = mock(GrpcSandboxReconciliationClient.class); var mvc = mvc(mode, owner);
        var admin = new UsernamePasswordAuthenticationToken("unrelated-admin", "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(admin);
        when(engine.check(any(), anyString(), any(), any())).thenReturn(false);
        mvc.perform(get(path()).principal(admin)).andExpect(status().isForbidden());
        SecurityContextHolder.getContext().setAuthentication(actor);
        mvc.perform(get(path()).principal(actor)).andExpect(status().isForbidden());
        SecurityContextHolder.clearContext(); mvc.perform(get(path())).andExpect(status().isUnauthorized());
        SecurityContextHolder.getContext().setAuthentication(actor);
        when(engine.check(any(), anyString(), any(), any())).thenThrow(new IllegalStateException("SECRET"));
        mvc.perform(get(path()).principal(actor)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RECONCILIATION_AUTHZ_UNAVAILABLE"));
        verifyNoInteractions(owner);
    }
    @Test void missingAccountIsNotZeroAndMissingAuthzEngineIsUnavailable() throws Exception {
        when(engine.check(any(), anyString(), any(), any())).thenReturn(true);
        mvc("enforce", client()).perform(get(path().replace(f.day.toString(), f.day.minusDays(1).toString())).principal(actor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.account").isEmpty()).andExpect(jsonPath("$.heldDelta").isEmpty())
                .andExpect(jsonPath("$.fundsStatus").value("ACCOUNT_MISSING"));
        var owner = mock(GrpcSandboxReconciliationClient.class);
        var noEngine = MockMvcBuilders.standaloneSetup(new SandboxReconciliationController(new AdvertiserAuthz("disabled", ConversionOnlineFixture.provider(null)), owner)).build();
        noEngine.perform(get(path()).principal(actor)).andExpect(status().isServiceUnavailable()); verifyNoInteractions(owner);
    }
    @Test void invalidRangeIs400AndInternalRpcAccessFailureIs503() throws Exception {
        when(engine.check(any(), anyString(), any(), any())).thenReturn(true);
        mvc("enforce", client()).perform(get(path().replace(f.day.toString(), "2026-02-31")).principal(actor)).andExpect(status().isBadRequest());
        var wrongCaller = client(); ReflectionTestUtils.setField(wrongCaller, "stub", f.stub("wrong-service"));
        mvc("enforce", wrongCaller).perform(get(path()).principal(actor)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_UNAVAILABLE"));
    }
    @Test void defaultOffAndEitherMissingSwitchInstallNoReadEntry() {
        var context = new ApplicationContextRunner().withUserConfiguration(SandboxReconciliationController.class, GrpcSandboxReconciliationClient.class);
        for (var properties : List.of(new String[0], new String[]{"recsys.ad.billing.sandbox-enabled=true"},
                new String[]{"recsys.advertiser.sandbox-reconciliation-enabled=true"})) {
            context.withPropertyValues(properties).run(c -> {
                assertNull(c.getStartupFailure()); assertEquals(0, c.getBeansOfType(SandboxReconciliationController.class).size());
                assertEquals(0, c.getBeansOfType(GrpcSandboxReconciliationClient.class).size());
            });
        }
    }
    @Test void malformedOwnerResponseCannotBecomeZeroOrSuccessfulReport() throws Exception {
        when(engine.check(any(), anyString(), any(), any())).thenReturn(true);
        var bad = mock(SandboxReconciliationServiceGrpc.SandboxReconciliationServiceBlockingStub.class);
        when(bad.inspect(any())).thenReturn(ReconciliationSnapshot.getDefaultInstance());
        var client = client(); ReflectionTestUtils.setField(client, "stub", bad);
        mvc("enforce", client).perform(get(path()).principal(actor)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_RESPONSE_INVALID"));
    }
}
