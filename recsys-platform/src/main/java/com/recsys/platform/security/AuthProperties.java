package com.recsys.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 全站安全配置(P0),前缀 {@code recsys.security}。
 * <ul>
 *   <li>{@code enabled}:总开关,{@code false}(nosec)时安全链退化为 permit-all 且方法级授权关闭,供本地讲解/联调。</li>
 *   <li>{@code internal-header}/{@code internal-secret}:东西向内部令牌 header 名与 HMAC 密钥(网关签发,下游校验)。</li>
 *   <li>{@code edge-secret}/{@code edge-ttl-seconds}:边缘终端 JWT(HS256)密钥与有效期,仅网关用。</li>
 *   <li>{@code permit-paths}:该服务的公开放行路径(只读接口、健康探针),其余需认证。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "recsys.security")
public class AuthProperties {

    private boolean enabled = true;
    private String internalHeader = "X-Internal-Auth";
    private String internalSecret = "recsys-dev-internal-secret-change-me";
    /** 边缘终端 JWT(HS256)签发/校验密钥,仅网关用;HS256 要求 ≥ 32 字节。生产从密钥分层注入,勿用默认值。 */
    private String edgeSecret = "recsys-dev-edge-jwt-secret-change-me-32bytes+";
    /** 边缘终端 JWT 有效期(秒),仅网关签发 login token 用。 */
    private long edgeTtlSeconds = 3600;
    private long tokenTtlSeconds = 300;
    private String serviceName = "recsys";
    private List<String> permitPaths = new ArrayList<>(List.of(
            "/actuator/health/**",
            "/actuator/info"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getInternalHeader() {
        return internalHeader;
    }

    public void setInternalHeader(String internalHeader) {
        this.internalHeader = internalHeader;
    }

    public String getInternalSecret() {
        return internalSecret;
    }

    public void setInternalSecret(String internalSecret) {
        this.internalSecret = internalSecret;
    }

    public String getEdgeSecret() {
        return edgeSecret;
    }

    public void setEdgeSecret(String edgeSecret) {
        this.edgeSecret = edgeSecret;
    }

    public long getEdgeTtlSeconds() {
        return edgeTtlSeconds;
    }

    public void setEdgeTtlSeconds(long edgeTtlSeconds) {
        this.edgeTtlSeconds = edgeTtlSeconds;
    }

    public long getTokenTtlSeconds() {
        return tokenTtlSeconds;
    }

    public void setTokenTtlSeconds(long tokenTtlSeconds) {
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public List<String> getPermitPaths() {
        return permitPaths;
    }

    public void setPermitPaths(List<String> permitPaths) {
        this.permitPaths = permitPaths == null ? new ArrayList<>() : permitPaths;
    }
}
