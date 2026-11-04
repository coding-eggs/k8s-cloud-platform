package com.coding.gateway.security;

import com.coding.common.components.permission.PermissionDecider;
import com.coding.gateway.route.PlatformRouteResolver;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 网关接口级鉴权（spec §8.3）：外部路径按路由表解析成 (service, 服务内路径)，交给与 api 共用的 {@link PermissionDecider}。
 *
 * <p>命中权限行但不持码时，把行上的码集放进 {@link #DENIED_CODES_ATTR}，供被拒审计记录「缺哪些码」。
 * 路由表里没有的路径直接拒（没有上游可去，也没有码可记）。
 */
public class GatewayAuthorizationManager implements ReactiveAuthorizationManager<AuthorizationContext> {

    /** exchange 属性：被拒时该端点要求的码集（{@code Set<String>}） */
    public static final String DENIED_CODES_ATTR = GatewayAuthorizationManager.class.getName() + ".deniedCodes";

    private final PlatformRouteResolver routes;
    private final PermissionDecider decider;

    public GatewayAuthorizationManager(PlatformRouteResolver routes, PermissionDecider decider) {
        this.routes = routes;
        this.decider = decider;
    }

    @Override
    public Mono<AuthorizationResult> authorize(Mono<Authentication> authentication, AuthorizationContext context) {
        ServerWebExchange exchange = context.getExchange();
        return authentication
                // AnonymousAuthenticationToken.isAuthenticated() 恒为 true，必须显式判掉
                .filter(a -> a.isAuthenticated() && !(a instanceof AnonymousAuthenticationToken))
                .map(a -> decide(exchange, a))
                .defaultIfEmpty(new AuthorizationDecision(false));
    }

    private AuthorizationResult decide(ServerWebExchange exchange, Authentication auth) {
        String method = exchange.getRequest().getMethod().name();
        return routes.resolve(exchange.getRequest().getPath().value())
                .map(r -> {
                    Set<String> authorities = auth.getAuthorities().stream()
                            .map(GrantedAuthority::getAuthority)
                            .collect(Collectors.toSet());
                    PermissionDecider.Decision d = decider.decide(
                            r.route().service(), method, r.internalPath(), authorities, false);
                    if (d instanceof PermissionDecider.Deny(Set<String> codes)) {
                        if (!codes.isEmpty()) {
                            exchange.getAttributes().put(DENIED_CODES_ATTR, codes);
                        }
                        return new AuthorizationDecision(false);
                    }
                    return new AuthorizationDecision(true);
                })
                .orElseGet(() -> new AuthorizationDecision(false));
    }
}
