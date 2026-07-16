package com.recsys.advertiser.authz;

import com.lrj.authz.protocol.AuthzEngine;
import com.lrj.authz.protocol.Consistency;
import com.lrj.authz.protocol.RelationshipUpdate;
import com.lrj.authz.protocol.ResourceRef;
import com.lrj.authz.protocol.SubjectRef;
import com.lrj.authz.protocol.ZedTokenView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 归属判权守卫的三态矩阵:disabled 零交互 / shadow 只记日志放行 / enforce 403 + 依赖故障 503 fail-closed。 */
class AdvertiserAuthzTest {

    private final AuthzEngine engine = mock(AuthzEngine.class);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private AdvertiserAuthz authz(String mode) {
        ObjectProvider<AuthzEngine> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(engine);
        return new AdvertiserAuthz(mode, provider);
    }

    private static void loginAs(String subject) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(subject, null, List.of()));
    }

    @Test
    void disabled_neverTouchesEngine() {
        AdvertiserAuthz a = authz("disabled");
        a.requireAdvertiser("edit", 1001);
        a.requirePlatform("administrate");
        a.onAdvertiserCreated(1001);
        assertThat(a.filterViewable(List.of("x"), s -> 1001)).containsExactly("x");
        verifyNoInteractions(engine);
    }

    @Test
    void invalidMode_failsFast() {
        assertThatThrownBy(() -> authz("enfoce")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforce_denied_throws403() {
        loginAs("u_owner2");
        when(engine.check(any(), anyString(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> authz("enforce").requireAdvertiser("edit", 1001))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void enforce_allowed_passes_andUsesSubjectFromSecurityContext() {
        loginAs("u_owner1");
        when(engine.check(any(), anyString(), any(), any())).thenReturn(true);

        assertThatCode(() -> authz("enforce").requireAdvertiser("edit", 1001)).doesNotThrowAnyException();

        verify(engine).check(eq(SubjectRef.user("u_owner1")), eq("edit"),
                eq(ResourceRef.of("advertiser", "1001")), any());
    }

    @Test
    void enforce_anonymous_throws403() {
        assertThatThrownBy(() -> authz("enforce").requireAdvertiser("view", 1001))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(engine);
    }

    @Test
    void enforce_engineFailure_throws503_failClosed() {
        loginAs("u_owner1");
        when(engine.check(any(), anyString(), any(), any())).thenThrow(new IllegalStateException("判权响应不可信"));

        assertThatThrownBy(() -> authz("enforce").requireAdvertiser("edit", 1001))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void shadow_denied_and_engineFailure_bothPass() {
        loginAs("u_owner2");
        AdvertiserAuthz a = authz("shadow");
        when(engine.check(any(), anyString(), any(), any())).thenReturn(false);
        assertThatCode(() -> a.requireAdvertiser("edit", 1001)).doesNotThrowAnyException();

        when(engine.check(any(), anyString(), any(), any())).thenThrow(new IllegalStateException("boom"));
        assertThatCode(() -> a.requireAdvertiser("edit", 1001)).doesNotThrowAnyException();
    }

    @Test
    void filterViewable_enforce_filters_shadow_passesThrough() {
        loginAs("u_owner1");
        ResourceRef a1 = ResourceRef.of("advertiser", "1001");
        ResourceRef a2 = ResourceRef.of("advertiser", "1002");
        when(engine.checkBulk(any(), eq("view"), anyList(), any()))
                .thenReturn(Map.of(a1, true, a2, false));

        assertThat(authz("enforce").filterViewable(List.of(1001L, 1002L), Long::longValue))
                .containsExactly(1001L);
        assertThat(authz("shadow").filterViewable(List.of(1001L, 1002L), Long::longValue))
                .containsExactly(1001L, 1002L);
    }

    @Test
    void filterViewable_enforce_anonymous_returnsEmpty() {
        assertThat(authz("enforce").filterViewable(List.of(1001L), Long::longValue)).isEmpty();
        verifyNoInteractions(engine);
    }

    @Test
    void onAdvertiserCreated_writesPlatformAndOwnerTuples_andLaterChecksCarryZedToken() {
        loginAs("u_owner1");
        when(engine.writeRelationships(anyList())).thenReturn(new ZedTokenView("zed-1"));
        when(engine.check(any(), anyString(), any(), any())).thenReturn(true);
        AdvertiserAuthz a = authz("enforce");

        a.onAdvertiserCreated(1001);

        ArgumentCaptor<List<RelationshipUpdate>> updates = ArgumentCaptor.forClass(List.class);
        verify(engine).writeRelationships(updates.capture());
        assertThat(updates.getValue()).hasSize(2);
        assertThat(updates.getValue()).anySatisfy(u -> {
            assertThat(u.relation()).isEqualTo("owner");
            assertThat(u.subject()).isEqualTo(SubjectRef.user("u_owner1"));
        });
        assertThat(updates.getValue()).anySatisfy(u ->
                assertThat(u.relation()).isEqualTo("platform"));

        // 写归属后,后续判读带 ZedToken(at_least_as_fresh),防量化快照漏读刚建的归属
        a.requireAdvertiser("view", 1001);
        ArgumentCaptor<Consistency> c = ArgumentCaptor.forClass(Consistency.class);
        verify(engine).check(any(), anyString(), any(), c.capture());
        assertThat(c.getValue().mode()).isEqualTo(Consistency.Mode.AT_LEAST_AS_FRESH);
        assertThat(c.getValue().zedToken()).isEqualTo("zed-1");
    }

    @Test
    void onAdvertiserCreated_enforce_writeFailure_throws503() {
        loginAs("u_owner1");
        when(engine.writeRelationships(anyList())).thenThrow(new IllegalStateException("down"));

        assertThatThrownBy(() -> authz("enforce").onAdvertiserCreated(1001))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
