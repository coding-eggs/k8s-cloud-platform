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
 *
 * <p>热加载：{@link #replaceRules} 原子换整表 —— 读方（每请求调 {@link #requiredCodes}）
 * 只看到整旧或整新快照，不会新旧混合；AntPathMatcher 线程安全，无需加锁。
 */
public final class PermissionRegistry {

    /** 一条规则：HTTP 方法 + Ant 风格路径 + 权限 code。 */
    public record Rule(String method, String pattern, String code) {}

    private volatile List<Rule> rules;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public PermissionRegistry(Collection<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /** 热加载：原子替换整表（不可变快照）。权限点 CRUD/重载在 DB 提交后调用。 */
    public void replaceRules(Collection<Rule> rules) {
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
     * **运行时判据**：规则方法与请求方法忽略大小写相等，且 Ant 路径命中。
     *
     * <p>{@link #requiredCodes} 与「按 URL 反查」（/permission/match）共用这一处判据 ——
     * 反查必须如实反映真实鉴权，不能比它更宽松。注意 {@code *} 方法在运行时**不是**通配；
     * {@link #matches} 认 {@code *} 是给反向核账用的，两者语义不同，不要混用。
     */
    public boolean runtimeMatches(Rule rule, String method, String path) {
        return rule.method().equalsIgnoreCase(method) && matcher.match(rule.pattern(), path);
    }

    /**
     * 返回匹配该 (method,path) 的权限 code 集合（多行 ANY-of）；无匹配返回 empty。
     * method 比较忽略大小写。
     */
    public Optional<Set<String>> requiredCodes(String method, String path) {
        Set<String> codes = new LinkedHashSet<>();
        for (Rule r : rules) {
            if (runtimeMatches(r, method, path)) {
                codes.add(r.code());
            }
        }
        return codes.isEmpty() ? Optional.empty() : Optional.of(codes);
    }
}
