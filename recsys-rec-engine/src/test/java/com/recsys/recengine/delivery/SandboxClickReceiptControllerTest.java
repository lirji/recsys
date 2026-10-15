package com.recsys.recengine.delivery;

import com.recsys.common.ad.billing.*;
import com.recsys.common.ad.billing.ClickSettlement.*;
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

/** 严格HTTP边界：等待与已收费区分；不把token/主体回传在可分享的状态快照。 */
@Tag("money-chain")
class SandboxClickReceiptControllerTest {
    AdServingGateway gateway;MockMvc mvc;
    UUID receipt=UUID.randomUUID(),qualification=UUID.randomUUID();
    String path="/api/sandbox/click-receipts/"+receipt;
    String input="{\"qualificationToken\":\"secret-proof\",\"kind\":\"CLICK\",\"visibleDurationMs\":0,\"minVisibleRatioBps\":0,\"pageVisible\":false}";
    UsernamePasswordAuthenticationToken admin=new UsernamePasswordAuthenticationToken("admin","unused",List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    @BeforeEach void setup() { gateway=mock(AdServingGateway.class);mvc=MockMvcBuilders.standaloneSetup(new SandboxClickReceiptController(gateway)).build(); }
    Result result(ReceiptStatus status) {
        var now=Instant.now();var p=new Prepared(qualification,Decision.HELD,LocalDate.now(),"0.123456",now,now.plusSeconds(300),now,"secret-proof");
        boolean accepted=status==ReceiptStatus.ACCEPTED_WAITING;
        return new Result(new Receipt(receipt,qualification,Kind.CLICK,status,accepted?receipt:null,accepted?now:null,now),
                new Snapshot(p,State.HELD,0,null,accepted?receipt:null,null,null,null,null));
    }
    @Test void anonymousNonAdminAndInvalidTypesNeverReachOwner() throws Exception {
        mvc.perform(put(path).contentType("application/json").content(input)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/sandbox/click-settlements/"+qualification).principal(new UsernamePasswordAuthenticationToken("u","x",List.of(new SimpleGrantedAuthority("ROLE_USER"))))).andExpect(status().isForbidden());
        for(String invalid:List.of(input.replace("\"CLICK\"","\"future\""),input.replace("\"CLICK\"","1"),input.replace("\"visibleDurationMs\":0","\"visibleDurationMs\":0.5"),
                input.replace("\"visibleDurationMs\":0","\"visibleDurationMs\":\"0\""),input.replace("false","\"false\""),input.replace("false","true"),input.replace("\"minVisibleRatioBps\":0","\"minVisibleRatioBps\":100"))) {
            mvc.perform(put(path).principal(admin).contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(gateway);
    }
    @Test void waitingAndPersistedRejectionAreNotFakeChargedOrZeroSuccess() throws Exception {
        when(gateway.acceptSandboxClick(any())).thenReturn(result(ReceiptStatus.ACCEPTED_WAITING));
        mvc.perform(put(path).principal(admin).contentType("application/json").content(input)).andExpect(status().isOk())
                .andExpect(jsonPath("$.settlement.progress").value("WAITING_FOR_VISIBLE")).andExpect(jsonPath("$.settlement.chargedAmount").isEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-proof"))));
        verify(gateway).acceptSandboxClick(argThat(c->c.subject().equals("admin")));
        when(gateway.acceptSandboxClick(any())).thenReturn(result(ReceiptStatus.POLICY_NOT_MET));
        mvc.perform(put(path).principal(admin).contentType("application/json").content(input)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.receipt.status").value("POLICY_NOT_MET"));
    }
    @Test void conflictPermissionsAndUnknownKeepDistinctStatusAndNoSensitiveMessages() throws Exception {
        doThrow(new ClickSettlement.Conflict()).when(gateway).acceptSandboxClick(any());
        mvc.perform(put(path).principal(admin).contentType("application/json").content(input)).andExpect(status().isConflict());
        doThrow(new SandboxDelivery.InvalidProof()).when(gateway).acceptSandboxClick(any());
        mvc.perform(put(path).principal(admin).contentType("application/json").content(input)).andExpect(status().isForbidden());
        doThrow(new IllegalStateException("private token")).when(gateway).acceptSandboxClick(any());
        mvc.perform(put(path).principal(admin).contentType("application/json").content(input)).andExpect(status().isServiceUnavailable())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private token"))));
        mvc.perform(get(path).principal(admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/sandbox/click-settlements/"+qualification).principal(admin)).andExpect(status().isNotFound());
    }
}
