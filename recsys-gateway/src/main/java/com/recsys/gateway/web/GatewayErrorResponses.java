package com.recsys.gateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.error.ErrorCode;
import com.recsys.platform.web.ApiError;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 网关错误体的唯一序列化入口，支持安全链、异常链和直接完成的限流拒绝。 */
@Component
public class GatewayErrorResponses {
    private final ObjectMapper mapper;

    public GatewayErrorResponses(ObjectMapper mapper) { this.mapper = mapper; }

    /** 状态来自协议，未知标准码仍使用安全兜底，不能透传框架detail。 */
    public Mono<Void> writeStatus(ServerWebExchange exchange, HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        String code = known == null ? ErrorCode.INTERNAL_ERROR.code() : known.name();
        return write(exchange, status, code, ErrorCode.forHttpStatus(status.value()).message());
    }

    /** 明确业务失败用稳定目录码；无HTTP状态的作业码不能变成成功响应。 */
    public Mono<Void> writeError(ServerWebExchange exchange, ErrorCode error) {
        if (error.httpStatus() == ErrorCode.NO_HTTP_STATUS) error = ErrorCode.INTERNAL_ERROR;
        return write(exchange, HttpStatusCode.valueOf(error.httpStatus()), error.code(), error.message());
    }

    private Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status, String code, String message) {
        return Mono.defer(() -> {
            var response = exchange.getResponse();
            if (response.isCommitted()) return Mono.empty();
            byte[] bytes;
            try {
                bytes = mapper.writeValueAsBytes(ApiError.of(code, message, exchange.getRequest().getPath().value(), null));
            } catch (JsonProcessingException failure) {
                return Mono.error(failure);
            }
            response.setStatusCode(status);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            response.getHeaders().setContentLength(bytes.length);
            // HEAD保留错误状态和表示长度，按HTTP语义不发送实体，避免Netty额外截断或二次提交。
            if (HttpMethod.HEAD.equals(exchange.getRequest().getMethod())) return response.setComplete();
            return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
        });
    }

    /** 仅补setComplete的空错误体；下游已写出的业务体、流式体与已提交响应均保持。 */
    public ServerWebExchange completeEmptyErrors(ServerWebExchange exchange) {
        var decorated = new ServerHttpResponseDecorator(exchange.getResponse()) {
            private boolean bodyStarted;

            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                bodyStarted = true;
                return super.writeWith(body);
            }

            @Override
            public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                bodyStarted = true;
                return super.writeAndFlushWith(body);
            }

            @Override
            public Mono<Void> setComplete() {
                return Mono.defer(() -> {
                    HttpStatusCode status = getStatusCode();
                    if (!bodyStarted && !isCommitted() && status != null && status.isError()) {
                        // 写给delegate，避免HEAD或序列化完成再次经过本装饰器造成递归。
                        return writeStatus(exchange.mutate().response(getDelegate()).build(), status);
                    }
                    return super.setComplete();
                });
            }
        };
        return exchange.mutate().response(decorated).build();
    }
}
