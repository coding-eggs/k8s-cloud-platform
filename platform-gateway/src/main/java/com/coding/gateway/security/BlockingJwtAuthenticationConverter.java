package com.coding.gateway.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 把 servlet 侧那个同步转换器（{@code PlatformJwtAuthenticationConverter}）搬到 boundedElastic 上跑：
 * 它要解析权限闭包，缓存未命中时会查 Redis / MySQL，是阻塞调用，不能占 Netty 事件循环。
 */
public class BlockingJwtAuthenticationConverter implements Converter<Jwt, Mono<AbstractAuthenticationToken>> {

    private final Converter<Jwt, AbstractAuthenticationToken> delegate;

    public BlockingJwtAuthenticationConverter(Converter<Jwt, AbstractAuthenticationToken> delegate) {
        this.delegate = delegate;
    }

    @Override
    public Mono<AbstractAuthenticationToken> convert(Jwt jwt) {
        return Mono.fromCallable(() -> delegate.convert(jwt)).subscribeOn(Schedulers.boundedElastic());
    }
}
