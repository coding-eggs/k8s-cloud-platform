package com.coding.common.components.jwt;

/** authority 前缀集中定义，避免 auth-server 写入、converter 展开、manager 校验三处字面量漂移。 */
public final class PermissionAuthorityNames {
    public static final String PLATFORM_PREFIX = "PLATFORM:";
    public static final String PERM_PREFIX = "PERM:";
    private PermissionAuthorityNames() {}
    public static String platform(String code) { return PLATFORM_PREFIX + code; }
    public static String perm(String code) { return PERM_PREFIX + code; }
}
