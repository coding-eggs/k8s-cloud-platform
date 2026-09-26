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

    /** 全部规则（不可变视图）—— 供 §5.3 反向核账（幽灵规则扫描）；鉴权路径勿依赖此 getter。 */
    public List<Rule> rules() {
        return rules;
    }

    /** pattern 是否 ant-match 给定实际路径（反向核账复用同一匹配语义）。 */
    public boolean matches(Rule rule, String method, String path) {
        return (rule.method().equalsIgnoreCase(method) || rule.method().equals("*") || method.equals("*"))
                && matcher.match(rule.pattern(), path);
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
