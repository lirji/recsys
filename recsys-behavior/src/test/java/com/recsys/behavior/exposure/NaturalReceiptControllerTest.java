package com.recsys.behavior.exposure;

import com.recsys.common.exposure.NaturalReceipt.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP 边界验证严格 JSON、原主体和未知结果，不用成功响应掩盖失败。 */
@Tag("natural-exposure")
class NaturalReceiptControllerTest {
    NaturalReceiptService service=mock(NaturalReceiptService.class);
    MockMvc mvc;
    UUID id=UUID.randomUUID(),qualification=UUID.randomUUID();
    String path="/api/sandbox/natural-impressions/"+id;
    String body="{\"qualificationToken\":\"opaque\",\"visibleDurationMs\":1200,\"minVisibleRatioBps\":6000,\"pageVisible\":true}";
    UsernamePasswordAuthenticationToken admin=new UsernamePasswordAuthenticationToken("admin","unused",List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    @BeforeEach void setup() { mvc=MockMvcBuilders.standaloneSetup(new NaturalReceiptController(service)).build(); }
    @Test void ownerRejectsAnonymousAndNonAdminEvenWithoutGateway() throws Exception {
        mvc.perform(put(path).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).principal(new UsernamePasswordAuthenticationToken("user","unused",List.of(new SimpleGrantedAuthority("ROLE_USER"))))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void strictFieldsRejectClientAttributionAndCoercion() throws Exception {
        for(String invalid:List.of(body.replace("1200","1.2"),body.replace("true","\"true\""),body.replace("6000","\"6000\""),
                body.replace("1200","300001"),body.replace("}",",\"itemId\":\"7\"}"),body.replace("true","null"))) {
            mvc.perform(put(path).principal(admin).contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Test void acceptedRejectedAndQueryResultsHaveNoMoneyFields() throws Exception {
        var accepted=new Result(id,qualification,Status.ACCEPTED,qualification,id,Instant.now(),Instant.now());
        when(service.accept(any())).thenReturn(accepted);
        mvc.perform(put(path).principal(admin).contentType("application/json").content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED")).andExpect(jsonPath("$.chargedAmount").doesNotExist());
        verify(service).accept(argThat(c->c.subject().equals("admin") && c.visibleDurationMs()==1200));
        var rejected=new Result(id,qualification,Status.EXPIRED,null,null,null,Instant.now());when(service.accept(any())).thenReturn(rejected);
        mvc.perform(put(path).principal(admin).contentType("application/json").content(body)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.acceptedAt").isEmpty());
        when(service.find(id,"admin")).thenReturn(Optional.of(rejected));mvc.perform(get(path).principal(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"));
    }
    @Test void failureAndNotYetObservedRemainDistinctWithoutLeakingSecrets() throws Exception {
        when(service.find(id,"admin")).thenReturn(Optional.empty());mvc.perform(get(path).principal(admin)).andExpect(status().isNotFound());
        for(var failure:List.of(new Conflict(),new AccessDenied(),new IllegalStateException("secret-token-and-sql"))) {
            doThrow(failure).when(service).accept(any());int expected=failure instanceof Conflict?409:failure instanceof AccessDenied?403:503;
            mvc.perform(put(path).principal(admin).contentType("application/json").content(body)).andExpect(status().is(expected))
                    .andExpect(jsonPath("$.code").value("NATURAL_RECEIPT_"+expected)).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-token-and-sql"))));
        }
    }
}
