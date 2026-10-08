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
 * - dataKey claim（TokenUserInfo）中的 platformRoles 非空 → {@link #PLATFORM_SCOPE_AUTHORITY}（「平台侧」标记）
 * - dataKey claim（TokenUserInfo）中的 permissions → {@code PERM:<code>}
 * - 标准 scope/roles claim 维持默认 JwtGrantedAuthoritiesConverter 行为
 * <p>
 * Spring Security 7 中资源服务器要求 {@code Converter<Jwt, AbstractAuthenticationToken>}。
 */
public class PlatformJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    /**
     * 平台域 authority 前缀，与租户域 {@code <tenant>:<code>} 区分
     */
    public static final String PLATFORM_AUTHORITY_PREFIX = "PLATFORM:";

    /**
     * 「调用方属于平台侧」的合成 authority：{@code platformRoles} 非空（= 持有任意 PLATFORM-scope 角色）时追加。
     * <p>
     * 判据在签发期就已收敛：{@code PlatformUserRoleMapper.selectRoleCodesByUser} 已 INNER JOIN
     * {@code platform_role} 并按 {@code scope='PLATFORM'} 过滤（见该 mapper XML 的注释），所以
     * <b>租户成员的 {@code platformRoles} 必为空</b> —— 本 authority 是精确的"平台侧"标记，不是近似。
     * k8s-server 侧据此替代原先硬编码角色名的 {@code PLATFORM:admin}。
     * <p>
     * 刻意<b>不带冒号</b>：{@code PLATFORM:<roleCode>} 恒含冒号，而 role code 是用户在角色管理页自建的
     * varchar(64)，因此两者构造上不可能相撞。
     */
    public static final String PLATFORM_SCOPE_AUTHORITY = "PLATFORM_SCOPE";

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
            boolean platformSide = false;
            Object roles = map.get("platformRoles");
            if (roles instanceof List<?> list) {
                for (Object role : list) {
                    if (role != null && !role.toString().isBlank()) {
                        authorities.add(new SimpleGrantedAuthority(PLATFORM_AUTHORITY_PREFIX + role));
                        platformSide = true;
                    }
                }
            }
            if (platformSide) {
                authorities.add(new SimpleGrantedAuthority(PLATFORM_SCOPE_AUTHORITY));
            }
            Object perms = map.get("permissions");
            if (perms instanceof List<?> plist) {
                for (Object p : plist) {
                    if (p != null && !p.toString().isBlank()) {
                        authorities.add(new SimpleGrantedAuthority(PermissionAuthorityNames.perm(p.toString())));
                    }
                }
            }
        }
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

}
