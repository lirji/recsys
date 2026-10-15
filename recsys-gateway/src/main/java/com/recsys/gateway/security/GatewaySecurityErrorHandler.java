package com.recsys.gateway.security;

import com.recsys.gateway.web.GatewayErrorResponses;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.oauth2.server.resource.BearerTokenErrorCodes;
import org.springframework.security.oauth2.server.resource.web.access.server.BearerTokenServerAccessDeniedHandler;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 复用框架Bearer状态/挑战语义，仅移除动态内部详情并补安全JSON。 */
@Component
public class GatewaySecurityErrorHandler implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {
    private final BearerTokenServerAuthenticationEntryPoint entryPoint = new BearerTokenServerAuthenticationEntryPoint();
    private final BearerTokenServerAccessDeniedHandler deniedHandler = new BearerTokenServerAccessDeniedHandler();
    private final GatewayErrorResponses responses;

    public GatewaySecurityErrorHandler(GatewayErrorResponses responses) { this.responses = responses; }

    /** invalid_request仍可能为400，invalid_token为401，不能统一强制成401。 */
    @Override public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException failure) {
        AuthenticationException safe = failure;
        if (failure instanceof OAuth2AuthenticationException oauth) {
            OAuth2Error original = oauth.getError();
            String code = original.getErrorCode().matches("[a-z][a-z0-9_]{0,63}")
                    ? original.getErrorCode() : BearerTokenErrorCodes.INVALID_TOKEN;
            OAuth2Error error = original instanceof BearerTokenError bearer
                    ? new BearerTokenError(code, bearer.getHttpStatus(), null, null, bearer.getScope())
                    : new OAuth2Error(code);
            safe = new OAuth2AuthenticationException(error);
        }
        return entryPoint.commence(responses.completeEmptyErrors(exchange), safe);
    }

    /** 框架仍按OAuth2主体添加insufficient_scope，不改变角色权限判定。 */
    @Override public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException failure) {
        return deniedHandler.handle(responses.completeEmptyErrors(exchange), failure);
    }
}
