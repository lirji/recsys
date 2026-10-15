package com.recsys.advertiser.authz;

import com.lrj.authz.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实守卫调用SDK接口验证收费边界策略，不声称替身证明SpiceDB关系数据正确。 */
@Tag("money-chain")
class AdvertiserConversionAuthzTest {
    final AuthzEngine engine=mock(AuthzEngine.class);
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    AdvertiserAuthz guard(String mode,AuthzEngine value) {
        ObjectProvider<AuthzEngine> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(value);
        return new AdvertiserAuthz(mode,provider);
    }
    void login() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("actor",null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
    @ParameterizedTest @ValueSource(strings={"disabled","shadow","enforce"})
    void everyModeRequiresCurrentResourcePermissionEvenForAdminAndRechecksRevocation(String mode) {
        login();var authz=guard(mode,engine);
        when(engine.check(any(),anyString(),any(),any())).thenReturn(true,false);
        assertEquals("actor",authz.requireConversionSubmission(9007199254740993L));
        var denied=assertThrows(ResponseStatusException.class,()->authz.requireConversionSubmission(9007199254740993L));
        assertEquals(HttpStatus.FORBIDDEN,denied.getStatusCode());
        verify(engine,times(2)).check(SubjectRef.user("actor"),"edit",ResourceRef.of("advertiser","9007199254740993"),Consistency.fullyConsistent());
    }
    @ParameterizedTest @ValueSource(strings={"disabled","shadow","enforce"})
    void missingOrBrokenEngineFailsClosedAndDoesNotLeakCause(String mode) {
        login();assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(ResponseStatusException.class,
                ()->guard(mode,null).requireConversionSubmission(1)).getStatusCode());
        when(engine.check(any(),anyString(),any(),any())).thenThrow(new IllegalStateException("secret-endpoint-response"));
        var failed=assertThrows(ResponseStatusException.class,()->guard(mode,engine).requireConversionSubmission(1));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,failed.getStatusCode());assertFalse(failed.getMessage().contains("secret-endpoint-response"));
    }
    @ParameterizedTest @ValueSource(strings={"disabled","shadow","enforce"})
    void anonymousAndInvalidScopeCannotReachAuthorizationDependency(String mode) {
        var authz=guard(mode,engine);
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,()->authz.requireConversionSubmission(1)).getStatusCode());
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("test","anonymous",List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,()->authz.requireConversionSubmission(1)).getStatusCode());
        login();assertThrows(IllegalArgumentException.class,()->authz.requireConversionSubmission(0));verifyNoInteractions(engine);
    }
}
