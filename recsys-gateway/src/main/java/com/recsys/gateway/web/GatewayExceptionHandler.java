package com.recsys.gateway.web;

import com.recsys.common.error.ErrorCode;
import com.recsys.common.error.PublicErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 响应式错误边界独立于Servlet Advice，任何内部detail/cause不得成为响应体。 */
@Component
public class GatewayExceptionHandler implements ErrorWebExceptionHandler, Ordered {
    private static final Logger LOG = LoggerFactory.getLogger(GatewayExceptionHandler.class);
    /** Boot默认错误处理器为-1，先使用项目协议；不改变WebFilter执行顺序。 */
    private static final int BEFORE_BOOT_ERROR_HANDLER = -2;
    private final GatewayErrorResponses responses;

    public GatewayExceptionHandler(GatewayErrorResponses responses) { this.responses = responses; }

    /** 已提交响应无法安全重写，传播原异常让服务器终止原流。 */
    @Override public Mono<Void> handle(ServerWebExchange exchange, Throwable failure) {
        if (exchange.getResponse().isCommitted()) return Mono.error(failure);
        if (failure instanceof PublicErrorException known) {
            if (known.errorCode().httpStatus() >= HttpStatus.INTERNAL_SERVER_ERROR.value()) logFailure(exchange, failure);
            return responses.writeError(exchange, known.errorCode());
        }
        if (failure instanceof ErrorResponse response) {
            var status = response.getStatusCode();
            if (!status.isError()) status = HttpStatus.INTERNAL_SERVER_ERROR;
            if (status.is5xxServerError()) logFailure(exchange, failure);
            exchange.getResponse().getHeaders().addAll(response.getHeaders());
            return responses.writeStatus(exchange, status);
        }
        if (failure instanceof AccessDeniedException) return responses.writeError(exchange, ErrorCode.FORBIDDEN);
        logFailure(exchange, failure);
        return responses.writeError(exchange, ErrorCode.INTERNAL_ERROR);
    }

    private void logFailure(ServerWebExchange exchange, Throwable failure) {
        LOG.error("Gateway failure method={} path={}", exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value(), failure);
    }

    /** 先于Boot默认错误页执行，同时保留其作为序列化故障后的最终兜底。 */
    @Override public int getOrder() { return BEFORE_BOOT_ERROR_HANDLER; }
}
