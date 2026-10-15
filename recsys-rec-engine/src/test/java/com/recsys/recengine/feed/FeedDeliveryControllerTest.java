package com.recsys.recengine.feed;

import com.recsys.common.exposure.*;
import com.recsys.recengine.feed.FeedDelivery.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 混排交付 HTTP 契约回归，大 ID、身份、202 和未知结果必须无损。 */
@Tag("natural-exposure")
class FeedDeliveryControllerTest {
    FeedDeliveryService service=mock(FeedDeliveryService.class);
    MockMvc mvc;
    String path="/api/sandbox/feed-deliveries/"+UUID.randomUUID();
    String body="{\"userId\":\"9223372036854775807\",\"size\":2,\"scene\":\"search\",\"query\":\"phone\"}";
    UsernamePasswordAuthenticationToken admin=new UsernamePasswordAuthenticationToken("admin","unused",List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    @BeforeEach void setup() { mvc=MockMvcBuilders.standaloneSetup(new FeedDeliveryController(service)).build(); }
    @Test void ownerRequiresAuthenticatedAdmin() throws Exception {
        mvc.perform(put(path).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).principal(new UsernamePasswordAuthenticationToken("user","unused",List.of(new SimpleGrantedAuthority("ROLE_USER"))))).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
    @Test void strictRequestRejectsNumericIdsFractionalSizesUnknownKindAndClientAttribution() throws Exception {
        for(String invalid:List.of(body.replace("\"9223372036854775807\"","9223372036854775807"),body.replace("\"size\":2","\"size\":1.2"),
                body.replace("\"size\":2","\"size\":51"),body.replace("}",",\"kind\":\"SEARCH\"}"),body.replace("}",",\"bucket\":\"fake\"}"))) {
            mvc.perform(put(path).principal(admin).contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Test void preparingUsesOriginalStringIdentityAndCompleteCanBeEmpty() throws Exception {
        var policy=new NaturalQualification.Policy("test-only",6000,1200,120000);
        when(service.deliver(any())).thenAnswer(call->new Operation(call.getArgument(0),new Settings(true,List.of(2),1,"b",.1,policy),State.PREPARING,1,null));
        mvc.perform(put(path).principal(admin).contentType("application/json").content(body)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.userId").value("9223372036854775807")).andExpect(jsonPath("$.items").isEmpty());
        verify(service).deliver(argThat(r->r.subject().equals("admin") && r.userId()==Long.MAX_VALUE));
    }
    @Test void failuresReturnSafeCodesAndKeepUnknownSeparate() throws Exception {
        for(var ex:List.of(new NaturalReceipt.Conflict(),new NaturalReceipt.AccessDenied(),new IllegalStateException("secret-sql"))) {
            doThrow(ex).when(service).deliver(any());int expected=ex instanceof NaturalReceipt.Conflict?409:ex instanceof NaturalReceipt.AccessDenied?403:503;
            mvc.perform(put(path).principal(admin).contentType("application/json").content(body)).andExpect(status().is(expected)).andExpect(jsonPath("$.code").value("FEED_DELIVERY_"+expected))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-sql"))));
        }
        when(service.find(any(),anyString())).thenReturn(Optional.empty());mvc.perform(get(path).principal(admin)).andExpect(status().isNotFound());
    }
}
