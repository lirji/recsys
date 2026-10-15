package com.recsys.recengine.delivery;

import com.recsys.common.ad.billing.ImpressionReceipt;
import com.recsys.common.ad.delivery.SandboxDelivery;
import com.recsys.recengine.ad.AdServingGateway;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 回执字段不宽松截断或强制转换；绕过全局安全时仍必须检查管理员。 */
@Tag("money-chain")
class SandboxImpressionControllerTest {
    AdServingGateway gateway;
    MockMvc mvc;
    UUID receipt=UUID.randomUUID();
    String path="/api/sandbox/ad-impressions/"+receipt;
    String input="{\"qualificationToken\":\"proof\",\"visibleDurationMs\":1000,\"minVisibleRatioBps\":5000,\"pageVisible\":true}";
    UsernamePasswordAuthenticationToken admin=new UsernamePasswordAuthenticationToken("admin","unused",List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    @BeforeEach void setup() { gateway=mock(AdServingGateway.class); mvc=MockMvcBuilders.standaloneSetup(new SandboxImpressionController(gateway)).build(); }
    @Test void anonymousNonAdminAndWrongTypesNeverReachOwner() throws Exception {
        mvc.perform(put(path).contentType("application/json").content(input)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).principal(new UsernamePasswordAuthenticationToken("u","x",List.of(new SimpleGrantedAuthority("ROLE_USER"))))).andExpect(status().isForbidden());
        for(String invalid:List.of(input.replace("1000","1000.5"),input.replace("1000","\"1000\""),input.replace("true","\"true\""),input.replace("5000","10001"))) {
            mvc.perform(put(path).principal(admin).contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(gateway);
    }
    @Test void acceptedHasExactAmountAndRejectedRemains422WithOriginalResult() throws Exception {
        var now=Instant.now();
        var result=new ImpressionReceipt.Result(receipt,UUID.randomUUID(),ImpressionReceipt.Status.ACCEPTED,UUID.randomUUID(),receipt,"0.123456",LocalDate.of(2026,10,14),now,now);
        when(gateway.acceptSandboxImpression(any())).thenReturn(result);
        mvc.perform(put(path).principal(admin).contentType("application/json").content(input)).andExpect(status().isOk())
                .andExpect(jsonPath("$.chargedAmount").value("0.123456")).andExpect(jsonPath("$.status").value("ACCEPTED"));
        verify(gateway).acceptSandboxImpression(argThat(c->c.subject().equals("admin")));
        var rejection=new ImpressionReceipt.Result(receipt,UUID.randomUUID(),ImpressionReceipt.Status.EXPIRED,null,null,"0.000000",null,null,now);
        when(gateway.acceptSandboxImpression(any())).thenReturn(rejection);
        mvc.perform(put(path).principal(admin).contentType("application/json").content(input)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }
    @Test void signatureConflictAndUnknownHaveDistinctRecoverableSemantics() throws Exception {
        doThrow(new SandboxDelivery.InvalidProof()).when(gateway).acceptSandboxImpression(any());
        mvc.perform(put(path).principal(admin).contentType("application/json").content(input)).andExpect(status().isForbidden());
        doThrow(new ImpressionReceipt.Conflict()).when(gateway).acceptSandboxImpression(any());
        mvc.perform(put(path).principal(admin).contentType("application/json").content(input)).andExpect(status().isConflict());
        doThrow(new IllegalStateException("private")).when(gateway).acceptSandboxImpression(any());
        mvc.perform(put(path).principal(admin).contentType("application/json").content(input)).andExpect(status().isServiceUnavailable())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
        mvc.perform(get(path).principal(admin)).andExpect(status().isNotFound());
    }
}
