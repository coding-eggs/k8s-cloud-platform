package com.coding.common.components.jwt;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * claim → authentication 转换器（platform-api / k8s-server 共用，避免两服务映射漂移）：
 * - dataKey claim（TokenUserInfo）中的 platformRoles → {@code PLATFORM:<code>}
 * - 标准 scope/roles claim 维持默认 JwtGrantedAuthoritiesConverter 行为
 * <p>
 * Spring Security 7 中资源服务器要求 {@code Converter<Jwt, AbstractAuthenticationToken>}。
 */
public class PlatformJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    /**
     * 平台域 authority 前缀，与租户域 {@code <tenant>:<code>} 区分
     */
    public static final String PLATFORM_AUTHORITY_PREFIX = "PLATFORM:";

    private final String dataKey;

    private final JwtGrantedAuthoritiesConverter defaultConverter = new JwtGrantedAuthoritiesConverter();

    public PlatformJwtAuthenticationConverter(String dataKey) {
        this.dataKey = dataKey;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new HashSet<>(defaultConverter.convert(jwt));
        Object data = jwt.getClaim(dataKey);
        if (data instanceof Map<?, ?> map) {
            Object roles = map.get("platformRoles");
            if (roles instanceof List<?> list) {
                for (Object role : list) {
                    if (role != null && !role.toString().isBlank()) {
                        authorities.add(new SimpleGrantedAuthority(PLATFORM_AUTHORITY_PREFIX + role));
                    }
                }
            }
        }
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

}
