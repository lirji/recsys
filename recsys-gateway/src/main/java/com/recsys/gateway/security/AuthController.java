package com.recsys.gateway.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.recsys.platform.security.AuthProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 演示用登录端点(P0)。自包含签发 HS256 终端 JWT,无需外部 IdP/JWK 服务器,便于本地一键演示完整的
 * 「登录取 token → 带 Bearer 访问受保护接口 → 网关校验 + 传播身份」闭环。
 *
 * <p><b>仅演示用</b>:用户存内存(admin/advertiser/user)。生产应替换为对接真实 IdP(OIDC/OAuth2)。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** 演示用户:username → (password, roles)。生产删除,改由 IdP 颁发。 */
    private static final Map<String, DemoUser> DEMO_USERS = Map.of(
            "admin", new DemoUser("admin", List.of("ADMIN")),
            "advertiser", new DemoUser("advertiser", List.of("ADVERTISER")),
            "user", new DemoUser("user", List.of("USER")));

    private final AuthProperties props;

    public AuthController(AuthProperties props) {
        this.props = props;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        DemoUser user = request.username() == null ? null : DEMO_USERS.get(request.username());
        if (user == null || !user.password().equals(request.password())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        long ttl = props.getEdgeTtlSeconds();
        String token = mint(request.username(), user.roles(), ttl);
        return new LoginResponse(token, "Bearer", ttl, user.roles());
    }

    private String mint(String subject, List<String> roles, long ttlSeconds) {
        try {
            JWSSigner signer = new MACSigner(props.getEdgeSecret().getBytes(StandardCharsets.UTF_8));
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .claim("roles", roles)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "令牌签发失败");
        }
    }

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(String token, String tokenType, long expiresInSeconds, List<String> roles) {
    }

    private record DemoUser(String password, List<String> roles) {
    }
}
