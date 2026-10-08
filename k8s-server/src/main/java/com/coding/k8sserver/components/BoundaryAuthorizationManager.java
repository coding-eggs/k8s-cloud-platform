package com.coding.k8sserver.components;

import com.coding.common.components.jwt.PlatformJwtAuthenticationConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import java.util.List;
import java.util.function.Supplier;

/**
 * k8s-server 侧唯一的授权判定：读目标 handler 自己声明的 {@link AccessBoundary}。
 *
 * <table>
 *   <tr><th>声明</th><th>本类的动作</th></tr>
 *   <tr><td>{@link AccessBoundary#PLATFORM}</td><td>要求 token 持有
 *       {@link PlatformJwtAuthenticationConverter#PLATFORM_SCOPE_AUTHORITY}，否则 403</td></tr>
 *   <tr><td>{@link AccessBoundary#TENANT}</td><td>放行；边界在 handler 内由
 *       {@link ResourceAccessResolver} 做（JWT tenantInfo + 分配表三元组 + client 选择）</td></tr>
 *   <tr><td>{@link AccessBoundary#UPSTREAM}</td><td>放行；收窄责任在上游服务层（当前仅 PV）</td></tr>
 * </table>
 *
 * <h2>为什么读 handler 而不是匹配路径</h2>
 * 曾经这里用 {@code /admin/** → PLATFORM:admin} 一条路径规则。它有三个毛病：把传输寻址当授权概念、
 * 硬编码 "admin" 角色名（与 platform-api 权限表不同源）、且只覆盖 14 个路由 —— 挂在 {@code /resources/**}
 * 下的集群级端点（nodes / persistentvolumes / storageclasses / clusterroles）因此完全裸奔。
 * 边界是资源自身的属性，基类已经表达了（现在由本类消费），不该再抄一份路径清单。
 *
 * <h2>fail-closed</h2>
 * handler 存在但读不到声明 → <b>拒绝</b>。未分类的端点会对所有人 403（含平台管理员），因此不会被忽略；
 * 这也是本层<b>不需要</b>启动期交叉校验的原因（对比 platform-api 的 {@code PermissionCrossCheckRunner}：
 * 那里的失败模式是漏配 = <i>静默放行</i>，才必须启动期兜底）。
 *
 * <p><b>未匹配到 handler</b>（404 / 静态资源 / 方法不支持）时不在这里拒 —— 交给 MVC 走它自己的语义，
 * 避免把 405 变成 403。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BoundaryAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    /**
     * 全部 {@link HandlerMapping} bean（Spring 按 {@code @Order} 排序注入，与 MVC 的匹配顺序一致）。
     * 不注入 {@code HandlerMappingIntrospector}：直接遍历少一层自动配置依赖，且能逐条吞掉
     * "这条 mapping 不认这个请求"的异常（如 POST 打到只支持 GET 的路径会抛 405）。
     */
    private final List<HandlerMapping> handlerMappings;

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authSupplier,
                                         RequestAuthorizationContext ctx) {
        Authentication auth = authSupplier.get();
        // AnonymousAuthenticationToken.isAuthenticated() 恒为 true（AnonymousAuthenticationFilter 默认开启），
        // 必须显式排除，否则未登录会走完下面的放行分支。
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return new AuthorizationDecision(false);
        }

        Object target = resolveTarget(ctx);
        if (target == null) {
            // 没有 handler 认这个请求：不在这里拒（否则 404/405 会变成 403）
            return new AuthorizationDecision(true);
        }

        AccessBoundary boundary = AccessBoundaryAware.boundaryOf(target);
        if (boundary == null) {
            log.error("[K8S] {} 未声明 AccessBoundary，拒绝（fail-closed）。"
                            + "新增 controller / WebSocket handler 必须实现 AccessBoundaryAware：{} {}",
                    target.getClass().getName(), ctx.getRequest().getMethod(), ctx.getRequest().getRequestURI());
            return new AuthorizationDecision(false);
        }
        if (boundary != AccessBoundary.PLATFORM) {
            return new AuthorizationDecision(true);
        }

        boolean platformSide = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(PlatformJwtAuthenticationConverter.PLATFORM_SCOPE_AUTHORITY::equals);
        if (!platformSide) {
            log.warn("[K8S] Blocked: 调用方 {} 无平台侧身份（data.platformRoles 为空），"
                            + "访问平台侧端点 {} {}", auth.getName(),
                    ctx.getRequest().getMethod(), ctx.getRequest().getRequestURI());
        }
        return new AuthorizationDecision(platformSide);
    }

    /**目标 handler（去掉包装）：{@code HandlerMethod} → 其 controller bean；
     *  WebSocket → 绕过 {@link WebSocketHttpRequestHandler} 与装饰器链取到真正的 handler。 */
    private Object resolveTarget(RequestAuthorizationContext ctx) {
        for (HandlerMapping mapping : handlerMappings) {
            HandlerExecutionChain chain;
            try {
                chain = mapping.getHandler(ctx.getRequest());
            } catch (Exception e) {
                // 该 mapping 不认这个请求（方法不支持 / 媒体类型不支持等），继续试下一个
                log.debug("[K8S] HandlerMapping {} 未匹配 {} {}: {}",
                        mapping.getClass().getSimpleName(), ctx.getRequest().getMethod(),
                        ctx.getRequest().getRequestURI(), e.getMessage());
                continue;
            }
            if (chain == null || chain.getHandler() == null) {
                continue;
            }
            Object handler = chain.getHandler();
            if (handler instanceof HandlerMethod handlerMethod) {
                return handlerMethod.getBean();
            }
            if (handler instanceof WebSocketHttpRequestHandler wsHandler) {
                // 必须剥装饰器链：WebSocketHandlerRegistry 注册的 handler 会被
                // WebSocketHandlerDecoratorFactory（默认 ExceptionWebSocketHandlerDecoratorFactory）包一层，
                // 只认最外层就读不到 AccessBoundaryAware 声明 → 握手被 403。
                return WebSocketHandlerDecorator.unwrap(wsHandler.getWebSocketHandler());
            }
            return handler;
        }
        return null;
    }
}
