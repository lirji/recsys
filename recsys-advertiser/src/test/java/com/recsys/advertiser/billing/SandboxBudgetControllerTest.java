package com.recsys.advertiser.billing;

import com.recsys.advertiser.AdvertiserRepository;
import com.recsys.common.ad.billing.*;
import com.recsys.platform.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 不加载方法安全的 MVC 契约测试，证明 nosec 也不能绕过新预算入口的显式授权。 */
@Tag("money-chain")
class SandboxBudgetControllerTest {
    private final AdvertiserRepository advertisers = mock(AdvertiserRepository.class);
    private final GrpcSandboxBudgetClient client = mock(GrpcSandboxBudgetClient.class);
    private MockMvc mvc;
    private final String commandId = UUID.randomUUID().toString();
    private final String route = "/api/advertiser/123/sandbox-budget/2026-10-15";
    private final UsernamePasswordAuthenticationToken admin = new UsernamePasswordAuthenticationToken("admin:trusted", "",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    @BeforeEach
    void setup() {
        when(advertisers.advertiserExists(123)).thenReturn(true);
        mvc = MockMvcBuilders.standaloneSetup(new SandboxBudgetController(advertisers, client))
                .defaultRequest(get("/").accept(MediaType.APPLICATION_JSON))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void anonymousAndNonAdminRejectedBeforeLookingUpAdvertiser() throws Exception {
        mvc.perform(put(route).contentType(MediaType.APPLICATION_JSON).content(body("\"1\""))).andExpect(status().isUnauthorized());
        var advertiser = new UsernamePasswordAuthenticationToken("adv", "", List.of(new SimpleGrantedAuthority("ROLE_ADVERTISER")));
        mvc.perform(put(route).principal(advertiser).contentType(MediaType.APPLICATION_JSON).content(body("\"1\"")))
                .andExpect(status().isForbidden());
        mvc.perform(get(route)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/advertiser/123/sandbox-budget-commands/" + commandId).principal(advertiser))
                .andExpect(status().isForbidden());
        verifyNoInteractions(client);
        verify(advertisers, never()).advertiserExists(anyLong());
    }

    @Test
    void inputRequiresStringMoneyCurrencyVersionAndValidDate() throws Exception {
        for (String amount : new String[]{"1.2", "null", "\"1e2\"", "\"-1\"", "\"0.0000001\""}) {
            mvc.perform(put(route).principal(admin).contentType(MediaType.APPLICATION_JSON).content(body(amount)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(put(route).principal(admin).contentType(MediaType.APPLICATION_JSON)
                .content(body("\"1\"").replace(",\"expectedVersion\":0", ""))).andExpect(status().isBadRequest());
        mvc.perform(put(route).principal(admin).contentType(MediaType.APPLICATION_JSON)
                .content(body("\"1\"").replace("CNY", "USD"))).andExpect(status().isBadRequest());
        mvc.perform(get(route.replace("2026-10-15", "2026-02-30")).principal(admin)).andExpect(status().isBadRequest());
        for (String version : new String[]{"1.5", "\"0\"", "9223372036854775808", "-1", "null"}) {
            mvc.perform(put(route).principal(admin).contentType(MediaType.APPLICATION_JSON)
                    .content(body("\"1\"").replace("\"expectedVersion\":0", "\"expectedVersion\":" + version)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(put(route).principal(admin).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(com.recsys.common.error.ErrorCode.BAD_REQUEST.message()));
        verifyNoInteractions(client);
    }

    @Test
    void administratorActorIsTrustedAndMoneyReturnsAsExactString() throws Exception {
        when(client.setLimit(any())).thenAnswer(invocation -> {
            BudgetCommand command = invocation.getArgument(0);
            org.junit.jupiter.api.Assertions.assertEquals("admin:trusted", command.actor());
            return result(BudgetCommandResult.Status.APPLIED);
        });
        mvc.perform(put(route).principal(admin).contentType(MediaType.APPLICATION_JSON)
                .content(body("\"1.123456\"").replace("}", ",\"actor\":\"forged\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.account.advertiserId").value("123"))
                .andExpect(jsonPath("$.account.limitAmount").value("1.123456"))
                .andExpect(jsonPath("$.account.trafficClass").value("SANDBOX"));
    }

    @Test
    void rejectedCommandIs409ButQueryOfResultIs200() throws Exception {
        var rejected = result(BudgetCommandResult.Status.VERSION_CONFLICT);
        when(client.setLimit(any())).thenReturn(rejected);
        when(client.getCommand(123, UUID.fromString(commandId))).thenReturn(rejected);
        mvc.perform(put(route).principal(admin).contentType(MediaType.APPLICATION_JSON).content(body("\"1\"")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value("VERSION_CONFLICT"));
        mvc.perform(get("/api/advertiser/123/sandbox-budget-commands/" + commandId).principal(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VERSION_CONFLICT"));
    }

    @Test
    void missingAdvertiserNeverCallsOwner() throws Exception {
        when(advertisers.advertiserExists(123)).thenReturn(false);
        mvc.perform(put(route).principal(admin).contentType(MediaType.APPLICATION_JSON).content(body("\"1\"")))
                .andExpect(status().isNotFound());
        verifyNoInteractions(client);
    }

    private String body(String amount) {
        return "{\"commandId\":\"" + commandId + "\",\"currency\":\"CNY\",\"limitAmount\":" + amount + ",\"expectedVersion\":0}";
    }

    private BudgetCommandResult result(BudgetCommandResult.Status status) {
        return new BudgetCommandResult(UUID.fromString(commandId), status, new BudgetAccount(123,
                LocalDate.of(2026, 10, 15), new BigDecimal("1.123456"), BigDecimal.ZERO, BigDecimal.ZERO, 1));
    }
}
