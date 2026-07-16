package com.recsys.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** casdoor 边缘模式的纯函数:aud 家族校验(方案C owner 绑定)与 groups→角色映射。 */
class GatewaySecurityConfigTest {

    private static final String BASE = "ragshared0client00000001";

    @Test
    void audienceOk_exactMatch() {
        assertThat(GatewaySecurityConfig.audienceOk(List.of(BASE), "recsys", List.of(BASE))).isTrue();
    }

    @Test
    void audienceOk_derivedFamily_boundToOwner() {
        // <base>-org-<owner> 且 owner 一致 → 放行
        assertThat(GatewaySecurityConfig.audienceOk(List.of(BASE + "-org-recsys"), "recsys", List.of(BASE))).isTrue();
        // 家族形式但 owner 不一致(A 租户派生 client 混用 B 租户 token)→ 拒
        assertThat(GatewaySecurityConfig.audienceOk(List.of(BASE + "-org-acme"), "recsys", List.of(BASE))).isFalse();
        // owner 缺失时家族形式不放行(fail-closed)
        assertThat(GatewaySecurityConfig.audienceOk(List.of(BASE + "-org-recsys"), null, List.of(BASE))).isFalse();
    }

    @Test
    void audienceOk_unknownAud_orNull_rejected() {
        assertThat(GatewaySecurityConfig.audienceOk(List.of("other-client"), "recsys", List.of(BASE))).isFalse();
        assertThat(GatewaySecurityConfig.audienceOk(null, "recsys", List.of(BASE))).isFalse();
    }

    @Test
    void audienceOk_familyFromOtherOrg_isAudValid_orgPinningIsSeparateValidator() {
        // 家族校验本身允许任意 org(aud 与 owner 自洽即可)——租户隔离由 organization 钉死校验负责(见 jwtDecoder)。
        assertThat(GatewaySecurityConfig.audienceOk(List.of(BASE + "-org-acme"), "acme", List.of(BASE))).isTrue();
    }

    @Test
    void rolesFromGroups_mapsShortName_ignoresUnmapped() {
        Map<String, String> map = Map.of("admins", "ADMIN", "advertisers", "ADVERTISER");

        Collection<GrantedAuthority> roles = GatewaySecurityConfig.rolesFromGroups(
                List.of("recsys/advertisers", "recsys/unknown-group", "admins"), map);

        assertThat(roles).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADVERTISER", "ROLE_ADMIN"); // 全路径取短名;短名直配;未映射忽略
    }
}
