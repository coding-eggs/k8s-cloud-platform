package com.coding.common.components.jwt;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 「某主体此刻的权限 code 闭包」的取数策略，供 {@link PlatformJwtAuthenticationConverter} 在
 * token 不带 {@code data.permissions} 时按需解析（2026-10-09 起 token 不再携带全量码表）。
 *
 * <p>为什么要留一层接口而不是让转换器直接查库：
 * <ul>
 *   <li>platform-api 需要它（唯一的 {@code PERM:} 判权方），并自带 TTL 缓存；</li>
 *   <li>k8s-server <b>不需要</b> —— 它判权只看 {@code PLATFORM_SCOPE} + 分配表，用
 *       {@link #noop()} 显式声明"本服务没有权限码语义"，把这个契约写在调用点而不是靠默认值。</li>
 * </ul>
 *
 * <p><b>失败语义</b>：实现方在拿不到数据时应<b>返回空集合</b>（fail-closed，调用方按"没有权限"处理），
 * 不要抛异常 —— 转换器抛异常会让整条认证链失败，把一次 DB 抖动放大成全站 401、把用户踢出登录。
 */
@FunctionalInterface
public interface JwtPermissionResolver {

    /**
     * @param username token 主体
     * @param tenantId 租户上下文；null/空 = 平台视图（只算平台族）
     * @return 权限 code 闭包；拿不到就返回空（不要返回 null，也不要抛）
     */
    List<String> resolve(String username, @Nullable String tenantId);

    /** 不解析：本服务不使用权限码（k8s-server）。 */
    static JwtPermissionResolver noop() {
        return (username, tenantId) -> List.of();
    }
}
