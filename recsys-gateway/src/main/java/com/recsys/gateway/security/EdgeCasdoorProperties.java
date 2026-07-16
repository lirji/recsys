package com.recsys.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 边缘认证接统一登录平台 Casdoor(recsys.security.casdoor.*,默认关)。
 * 开启后网关不再校验自签 HS256 终端 JWT,改用 Casdoor JWKS(RS256)验签 + iss/aud 校验;
 * 内部令牌 subject 自动变为 Casdoor {@code sub}(UUID),下游(含 SpiceDB 判权主体)零改动跟随。
 * aud 支持方案C 派生家族:配 base client_id 即放行 {@code <base>-org-<org>},并绑定 owner=org 防跨租户混用。
 */
@ConfigurationProperties(prefix = "recsys.security.casdoor")
public class EdgeCasdoorProperties {

    private boolean enabled = false;
    private String jwkSetUri = "http://localhost:8000/.well-known/jwks";
    private String issuer = "http://localhost:8000";
    /** 本网关服务的租户(Casdoor org):token 的 owner 必须等于它(空=不校验,仅本地调试)。防跨租户 token 混入。 */
    private String organization = "recsys";
    /** aud 白名单:精确 client_id,或 shared app 的 base(按 {@code <base>-org-<owner>} 家族放行)。空=跳过 aud 校验(仅本地)。 */
    private List<String> audiences = new ArrayList<>(List.of("ragshared0client00000001"));
    /** Casdoor 组(shortName)→ 网关角色映射;未映射的组忽略。 */
    private Map<String, String> groupRoles = new LinkedHashMap<>(Map.of(
            "admins", "ADMIN",
            "advertisers", "ADVERTISER"));

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getJwkSetUri() { return jwkSetUri; }
    public void setJwkSetUri(String jwkSetUri) { this.jwkSetUri = jwkSetUri; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public String getOrganization() { return organization; }
    public void setOrganization(String organization) { this.organization = organization; }
    public List<String> getAudiences() { return audiences; }
    public void setAudiences(List<String> audiences) { this.audiences = audiences; }
    public Map<String, String> getGroupRoles() { return groupRoles; }
    public void setGroupRoles(Map<String, String> groupRoles) { this.groupRoles = groupRoles; }
}
