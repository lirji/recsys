package com.recsys.recengine.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.ad.billing.Reservation;
import com.recsys.common.ad.delivery.SandboxDelivery;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 直接绕过全局安全也必须拒绝匿名与非管理员；未知结果使用 503 保留原 ID。 */
@Tag("money-chain")
class SandboxDeliveryControllerTest {
    DeliveryOperationService service;
    MockMvc mvc;
    String path = "/api/sandbox/search-ad-deliveries/" + UUID.randomUUID();
    String input = "{\"userId\":\"1\",\"query\":\"phone\",\"slots\":2,\"scene\":\"search\"}";
    UsernamePasswordAuthenticationToken admin = new UsernamePasswordAuthenticationToken("admin", "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    @BeforeEach void setup() {
        service = mock(DeliveryOperationService.class); mvc = MockMvcBuilders.standaloneSetup(new SandboxDeliveryController(service)).build();
    }
    @Test void anonymousAndNonAdminCannotCreateOrQuery() throws Exception {
        mvc.perform(put(path).contentType("application/json").content(input)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).principal(new UsernamePasswordAuthenticationToken("user", "unused", List.of(new SimpleGrantedAuthority("ROLE_USER")))))
                .andExpect(status().isForbidden()); verifyNoInteractions(service);
    }
    @Test void authenticatedSubjectAndStrictInputAreEnforced() throws Exception {
        when(service.deliver(any())).thenAnswer(i -> new DeliveryOperation(i.getArgument(0), "b", .1, DeliveryOperation.State.COMPLETE, 1, List.of()));
        mvc.perform(put(path).principal(admin).contentType("application/json").content(input)).andExpect(status().isOk())
                .andExpect(jsonPath("$.trafficClass").value("SANDBOX")).andExpect(jsonPath("$.state").value("COMPLETE"));
        verify(service).deliver(argThat(r -> r.subject().equals("admin") && r.userId() == 1));
        mvc.perform(put(path).principal(admin).contentType("application/json").content(input.replace("\"userId\":\"1\"", "\"userId\":1"))).andExpect(status().isBadRequest());
        mvc.perform(put(path).principal(admin).contentType("application/json").content(input.replace("\"slots\":2", "\"slots\":1.5"))).andExpect(status().isBadRequest());
    }
    @Test void conflictForbiddenAndDependencyUnknownUseDistinctStatus() throws Exception {
        doThrow(new SandboxDelivery.Conflict()).when(service).deliver(any());
        mvc.perform(put(path).principal(admin).contentType("application/json").content(input)).andExpect(status().isConflict());
        doThrow(new Reservation.AccessDenied()).when(service).deliver(any());
        mvc.perform(put(path).principal(admin).contentType("application/json").content(input)).andExpect(status().isForbidden());
        doThrow(new IllegalStateException("internal secret")).when(service).deliver(any());
        mvc.perform(put(path).principal(admin).contentType("application/json").content(input)).andExpect(status().isServiceUnavailable())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("internal secret"))));
    }
}
