package com.coding.gateway.security;

import com.coding.common.exception.EnumResponseType;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/** 401/403 响应体，与 platform-api 过滤器链层的 ResponseData JSON 同形（前端按 code 分支）。 */
final class AuthJsonResponses {

    private AuthJsonResponses() {
    }

    static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, EnumResponseType type) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        byte[] body = ("{\"code\":" + type.getCode() + ",\"msg\":\"" + type.getMsg() + "\",\"data\":null}")
                .getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
