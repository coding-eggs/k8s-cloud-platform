package com.coding.platformapi.security;

import org.springframework.util.AntPathMatcher;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * URL → 权限 code 注册表（表驱动，ANY-of 语义）。
 * 启动时由 {@link PermissionRegistryFactory} 从 platform_permission（API 域）加载。
 */
public final class PermissionRegistry {

    /** 一条规则：HTTP 方法 + Ant 风格路径 + 权限 code。 */
    public record Rule(String method, String pattern, String code) {}

    private final List<Rule> rules;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public PermissionRegistry(Collection<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * 返回匹配该 (method,path) 的权限 code 集合（多行 ANY-of）；无匹配返回 empty。
     * method 比较忽略大小写。
     */
    public Optional<Set<String>> requiredCodes(String method, String path) {
        Set<String> codes = new LinkedHashSet<>();
        for (Rule r : rules) {
            if (r.method().equalsIgnoreCase(method) && matcher.match(r.pattern(), path)) {
                codes.add(r.code());
            }
        }
        return codes.isEmpty() ? Optional.empty() : Optional.of(codes);
    }
}
