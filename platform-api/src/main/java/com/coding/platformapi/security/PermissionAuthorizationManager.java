package com.coding.platformapi.security;

import com.coding.common.components.permission.PermissionDecider;
import com.coding.common.components.permission.PermissionRegistry;
import com.coding.common.components.permission.PlatformServices;
import com.coding.platformapi.audit.AuditDenied;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * 表驱动授权（spec §5.1）：命中权限行（ANY-of PERM）＞ 豁免前缀（authenticated 即过）＞ 默认拒绝。
 * 判定本身在 platform-common 的 {@link PermissionDecider}（网关用同一份），本类只做 servlet 适配；
 * 权限表由 {@link PermissionRegistry} 启动时加载（只含 service=platform-api 的行）。
 *
 * SS7 注意：AuthorizationManager 的抽象方法是 authorize(Supplier&lt;? extends Authentication&gt;, T)
 * 返回 AuthorizationResult（旧 check(...) 已不存在），verify 为 default。
 */
@Component
public class PermissionAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final PermissionDecider decider;

    public PermissionAuthorizationManager(PermissionRegistry registry) {
        this.decider = new PermissionDecider(registry);
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authSupplier,
                                         RequestAuthorizationContext ctx) {
        Authentication auth = authSupplier.get();
        // AnonymousAuthenticationToken.isAuthenticated() 恒为 true（AnonymousAuthenticationFilter 默认开启），
        // 故须显式排除匿名，否则未登录可命中豁免路径（spec §5.1 豁免=仅 authenticated）。
        boolean anonymous = auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken;
        List<String> authorities = anonymous ? List.of()
                : auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
        PermissionDecider.Decision d = decider.decide(PlatformServices.API,
                ctx.getRequest().getMethod(), pathOf(ctx), authorities, anonymous);
        if (d instanceof PermissionDecider.Deny deny) {
            // 审计：AccessDeniedException 不带权限码，这里把被判的码放进请求，
            // 由 ResourceServerConfig 的 accessDeniedHandler 读出来落一条 DENIED 记录（S0.1 spec §2）。
            // 匿名（走 401，有意不记）与「无权限行且不豁免」（§5.3 交叉校验本不该让它发生）都没有码可记。
            if (!deny.requiredCodes().isEmpty()) {
                AuditDenied.mark(ctx.getRequest(), deny.requiredCodes());
            }
            return new AuthorizationDecision(false);
        }
        return new AuthorizationDecision(true);
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
