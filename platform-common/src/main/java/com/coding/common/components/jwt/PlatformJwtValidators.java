package com.coding.common.components.jwt;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 资源服务器（platform-api / k8s-server）共用的 JWT 校验器：默认的 exp/nbf + aud 白名单。
 *
 * <p>为什么要自己装：两个模块都声明了自定义 {@code JwtDecoder} bean，Spring Boot 的自动配置因
 * {@code @ConditionalOnMissingBean} 退场，{@code spring.security.oauth2.resourceserver.jwt.audiences}
 * 这类属性<b>没人读</b>。曾经 k8s-server 配着 {@code audiences: gateway-code-client} 却从未生效
 * （前端真实 client_id 是 platform-web-client，真生效的话会全站 401）。
 *
 * <p>为什么校验 aud：资源服务器判权只看用户身份 claim（dataKey / sub），不看 scope 与签发给哪个客户端。
 * 不校验 aud 时，auth server 给任意客户端签发的用户 token 都等价于该用户的完整平台权限。
 *
 * <p>为什么不校验 iss：验签密钥只来自一个 jwk-set-uri，签名通过即已证明签发方；而 issuer 由 auth server
 * 按请求 host 现算（同源入口 / 内网 IP / https 前门各不相同），硬校验只会因换入口而全站 401。
 */
public final class PlatformJwtValidators {

    private PlatformJwtValidators() {
    }

    /**
     * @param allowedAudiences 允许的 aud（通常 = 平台自有客户端的 client_id）；token 的 aud 与之有交集即通过
     * @throws IllegalStateException 白名单为空 —— 拒启，而不是静默退化成「不校验」
     */
    public static OAuth2TokenValidator<Jwt> withAudiences(Collection<String> allowedAudiences) {
        Set<String> allowed = allowedAudiences == null ? Set.of() : allowedAudiences.stream()
                .filter(a -> a != null && !a.isBlank())
                .map(String::trim)
                .collect(Collectors.toUnmodifiableSet());
        if (allowed.isEmpty()) {
            throw new IllegalStateException(
                    "spring.security.oauth2.resourceserver.jwt.audiences 未配置：资源服务器必须声明允许的 aud（平台客户端 client_id）");
        }
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.stream().anyMatch(allowed::contains));
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), audience);
    }
}
