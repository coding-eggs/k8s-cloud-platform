package com.coding.common.components.jwt;

import lombok.extern.slf4j.Slf4j;
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
import java.util.Objects;
import java.util.Set;

/**
 * claim → authentication 转换器（platform-api / k8s-server 共用，避免两服务映射漂移）：
 * - dataKey claim（TokenUserInfo）中的 platformRoles → {@code PLATFORM:<code>}
 * - dataKey claim（TokenUserInfo）中的 platformRoles 非空 → {@link #PLATFORM_SCOPE_AUTHORITY}（「平台侧」标记）
 * - 权限 code → {@code PERM:<code>}：优先用 claim 自带的 {@code permissions}（兼容/回滚期），
 *   否则交给 {@link JwtPermissionResolver} 按需解析（2026-10-09 起为常态）
 * - 标准 scope/roles claim 维持默认 JwtGrantedAuthoritiesConverter 行为
 *
 * <p><b>为什么不再默认把权限闭包塞进 token</b>：那是把"无上限的列表"（长度 = 产品权限点总数）
 * 放进"有硬上限的容器"（WS 握手 query 4KB、HTTP 头 8KB），并且带 1 小时保鲜期 ——
 * 规则是热加载的而主体不热。改为请求期解析后，token 只留身份/角色/租户上下文，
 * 陈旧上界由解析侧的 TTL 缓存决定（见 platform-api 的 PermissionClosureResolver）。
 *
 * <p>Spring Security 7 中资源服务器要求 {@code Converter<Jwt, AbstractAuthenticationToken>}。
 */
@Slf4j
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

    /** claim 里权限闭包的字段名（TokenUserInfo.permissions） */
    private static final String PERMISSIONS_CLAIM = "permissions";

    private final String dataKey;

    /** null = 不解析（等价于 {@link JwtPermissionResolver#noop()}；仅测试与老构造场景用） */
    private final JwtPermissionResolver permissionResolver;

    private final JwtGrantedAuthoritiesConverter defaultConverter = new JwtGrantedAuthoritiesConverter();

    public PlatformJwtAuthenticationConverter(String dataKey, JwtPermissionResolver permissionResolver) {
        this.dataKey = dataKey;
        this.permissionResolver = permissionResolver;
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
            String username = text(map.get("username"));
            for (String code : permissionCodes(jwt, map, username, tenantIdOf(map))) {
                authorities.add(new SimpleGrantedAuthority(PermissionAuthorityNames.perm(code)));
            }
        }
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    /**
     * 权限 code 的来源：<b>claim 有就用 claim</b>（部署过渡期与回滚场景，老 token 仍带闭包），
     * 否则按需解析。解析失败按"没有权限码"处理（fail-closed），绝不抛 ——
     * 见 {@link JwtPermissionResolver} 的失败语义说明。
     */
    private List<String> permissionCodes(Jwt jwt, Map<?, ?> map, String username, String tenantId) {
        Object claim = map.get(PERMISSIONS_CLAIM);
        if (claim instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        if (permissionResolver == null) {
            return List.of();
        }
        String subject = username != null ? username : jwt.getSubject();
        try {
            List<String> resolved = permissionResolver.resolve(subject, tenantId);
            return resolved == null ? List.of() : resolved;
        } catch (Exception e) {
            log.error("权限闭包解析失败，按无权限码处理（fail-closed）: user={}, tenant={}", subject, tenantId, e);
            return List.of();
        }
    }

    /** data.tenantInfo.tenantId；平台视图的 base token 没有它 → null（= 只算平台族） */
    private static String tenantIdOf(Map<?, ?> map) {
        Object tenantInfo = map.get("tenantInfo");
        return tenantInfo instanceof Map<?, ?> t ? text(t.get("tenantId")) : null;
    }

    private static String text(Object o) {
        return o == null ? null : o.toString();
    }
}
