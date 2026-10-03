package com.coding.platformapi.security;

import com.coding.common.components.jwt.PermissionAuthorityNames;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.function.Supplier;

/**
 * 表驱动授权（spec §5.1）：命中权限行（ANY-of PERM）＞ 豁免前缀（authenticated 即过）＞ 默认拒绝。
 * 取代原先一刀切的 PLATFORM:admin 要求；权限表由 {@link PermissionRegistry} 启动时加载。
 *
 * SS7 注意：AuthorizationManager 的抽象方法是 authorize(Supplier&lt;? extends Authentication&gt;, T)
 * 返回 AuthorizationResult（旧 check(...) 已不存在），verify 为 default。
 */
@Component
public class PermissionAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final PermissionRegistry registry;

    public PermissionAuthorizationManager(PermissionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authSupplier,
                                         RequestAuthorizationContext ctx) {
        Authentication auth = authSupplier.get();
        // AnonymousAuthenticationToken.isAuthenticated() 恒为 true（AnonymousAuthenticationFilter 默认开启），
        // 故须显式排除匿名，否则未登录可命中豁免路径（spec §5.1 豁免=仅 authenticated）。
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return new AuthorizationDecision(false);
        }
        String method = ctx.getRequest().getMethod();
        String path = pathOf(ctx);
        // 权限行命中优先于豁免（spec §5.1）：行可收紧豁免路径；命中行但无 code → 直接拒，不回落豁免
        Set<String> required = registry.requiredCodes(method, path).orElse(null);
        if (required != null) {
            for (String code : required) {
                String authority = PermissionAuthorityNames.perm(code);
                boolean ok = auth.getAuthorities().stream()
                        .anyMatch(g -> g.getAuthority().equals(authority));
                if (ok) {
                    return new AuthorizationDecision(true);
                }
            }
            return new AuthorizationDecision(false);
        }
        if (ExemptPaths.isExempt(path)) {
            return new AuthorizationDecision(true);
        }
        return new AuthorizationDecision(false); // 默认拒绝（§5.3 交叉校验本不应让这发生）
    }

    private String pathOf(RequestAuthorizationContext ctx) {
        String uri = ctx.getRequest().getRequestURI();
        String cp = ctx.getRequest().getContextPath();
        if (cp != null && !cp.isEmpty() && uri.startsWith(cp)) {
            uri = uri.substring(cp.length());
        }
        return uri;
    }
}
