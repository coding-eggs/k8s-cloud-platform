package com.coding.k8sserver.components;

import com.coding.common.components.jwt.PlatformJwtAuthenticationConverter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.handler.ExceptionWebSocketHandlerDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BoundaryAuthorizationManager} 的决策表：这是 k8s-server 侧唯一的授权判定点，
 * 历史 bug（集群级端点裸奔 / 自定义平台角色被 403）都出在这里，故逐条钉住。
 */
class BoundaryAuthorizationManagerTest {

    private static final String PLATFORM_SCOPE = PlatformJwtAuthenticationConverter.PLATFORM_SCOPE_AUTHORITY;

    // ---- 目标 handler 的三种形态 ----

    /** 普通 controller（集群级 → PLATFORM） */
    private static final class PlatformController implements AccessBoundaryAware {
        @Override
        public AccessBoundary accessBoundary() {
            return AccessBoundary.PLATFORM;
        }

        @SuppressWarnings("unused")
        public void endpoint() {
        }
    }

    /** 命名空间级 controller（→ TENANT） */
    private static final class TenantController implements AccessBoundaryAware {
        @Override
        public AccessBoundary accessBoundary() {
            return AccessBoundary.TENANT;
        }

        @SuppressWarnings("unused")
        public void endpoint() {
        }
    }

    /** 租户可见的集群级资源（当前仅 PV，→ UPSTREAM） */
    private static final class UpstreamController implements AccessBoundaryAware {
        @Override
        public AccessBoundary accessBoundary() {
            return AccessBoundary.UPSTREAM;
        }

        @SuppressWarnings("unused")
        public void endpoint() {
        }
    }

    /** 未声明边界的 controller：模拟"新 controller 忘了实现接口" */
    private static final class UndeclaredController {
        @SuppressWarnings("unused")
        public void endpoint() {
        }
    }

    /** 模拟 PodExecWebSocketHandler：WS handler 自己声明边界 */
    private static final class TenantWsHandler extends TextWebSocketHandler implements AccessBoundaryAware {
        @Override
        public AccessBoundary accessBoundary() {
            return AccessBoundary.TENANT;
        }
    }

    private HandlerMethod handlerMethodOf(Object bean) throws NoSuchMethodException {
        Method m = bean.getClass().getMethod("endpoint");
        return new HandlerMethod(bean, m);
    }

    /** 单条 HandlerMapping；handler/lookup 决定"匹配到什么" */
    private static HandlerMapping mapping(Object handler) {
        return new HandlerMapping() {
            @Override
            public HandlerExecutionChain getHandler(HttpServletRequest request) {
                return handler == null ? null : new HandlerExecutionChain(handler);
            }
        };
    }

    private static HandlerMapping throwingMapping() {
        return new HandlerMapping() {
            @Override
            public HandlerExecutionChain getHandler(HttpServletRequest request) {
                throw new IllegalStateException("该 mapping 不认这个请求");
            }
        };
    }

    private AuthorizationResult authorize(List<HandlerMapping> mappings, Authentication auth) {
        return new BoundaryAuthorizationManager(mappings)
                .authorize(() -> auth, new RequestAuthorizationContext(new MockHttpServletRequest()));
    }

    private static Authentication token(String... authorities) {
        return new UsernamePasswordAuthenticationToken("u", "n/a", AuthorityUtils.createAuthorityList(authorities));
    }

    // ---- 决策表 ----

    @Test
    void anonymous_is_denied() {
        // AnonymousAuthenticationToken.isAuthenticated() 恒为 true，必须显式排除
        Authentication anon = new AnonymousAuthenticationToken("k", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        assertThat(authorize(List.of(mapping(new PlatformController())), anon).isGranted()).isFalse();
    }

    @Test
    void platform_endpoint_denied_without_platform_scope() throws Exception {
        // 租户成员的 token（哪怕带 tenantInfo）也不持有 PLATFORM_SCOPE —— 端点属性优先于 token 属性
        AuthorizationResult r = authorize(List.of(mapping(handlerMethodOf(new PlatformController()))),
                token("PERM:tenant:workload:list"));
        assertThat(r.isGranted()).isFalse();
    }

    @Test
    void platform_endpoint_granted_with_any_platform_role() throws Exception {
        // 判据是 PLATFORM_SCOPE（= platformRoles 非空），不是硬编码的 admin 角色名
        AuthorizationResult r = authorize(List.of(mapping(handlerMethodOf(new PlatformController()))),
                token(PLATFORM_SCOPE, "PLATFORM:audit-reader"));
        assertThat(r.isGranted()).isTrue();
    }

    @Test
    void tenant_endpoint_granted_without_platform_scope() throws Exception {
        // 边界在 handler 内由 ResourceAccessResolver 做（分配表三元组），本层不拦
        AuthorizationResult r = authorize(List.of(mapping(handlerMethodOf(new TenantController()))),
                token("PERM:tenant:workload:list"));
        assertThat(r.isGranted()).isTrue();
    }

    @Test
    void upstream_endpoint_granted() throws Exception {
        // 当前仅 PV：集群级但租户可见，收窄在上游服务层
        AuthorizationResult r = authorize(List.of(mapping(handlerMethodOf(new UpstreamController()))),
                token("PERM:tenant:persistentvolume:list"));
        assertThat(r.isGranted()).isTrue();
    }

    @Test
    void unclassified_handler_is_denied_fail_closed() throws Exception {
        // 未实现 AccessBoundaryAware → 拒绝（对所有人 403，包括平台管理员，故不会被忽略）
        AuthorizationResult r = authorize(List.of(mapping(handlerMethodOf(new UndeclaredController()))),
                token(PLATFORM_SCOPE));
        assertThat(r.isGranted()).isFalse();
    }

    @Test
    void websocket_handshake_uses_inner_handler_declaration() {
        // /ws/pod/exec 的 handler 是 WebSocketHttpRequestHandler，而且里面那层**还被装饰器包着**
        // （WebSocketHandlerRegistry 的默认 ExceptionWebSocketHandlerDecoratorFactory）。
        // 两步都得剥，否则读不到声明 → 握手被 403。
        WebSocketHandler decorated = new ExceptionWebSocketHandlerDecorator(new TenantWsHandler());
        WebSocketHttpRequestHandler wrapper = new WebSocketHttpRequestHandler(decorated);

        AuthorizationResult r = authorize(List.of(mapping(wrapper)), token("PERM:tenant:pod:exec"));
        assertThat(r.isGranted()).isTrue();
    }

    @Test
    void undeclared_websocket_handler_is_denied() {
        // 新增 WS 端点忘了实现 AccessBoundaryAware → 握手拒绝（fail-closed，与 HTTP 端点同口径）
        WebSocketHttpRequestHandler wrapper =
                new WebSocketHttpRequestHandler(new ExceptionWebSocketHandlerDecorator(new TextWebSocketHandler()));

        AuthorizationResult r = authorize(List.of(mapping(wrapper)), token(PLATFORM_SCOPE));
        assertThat(r.isGranted()).isFalse();
    }

    @Test
    void no_matching_handler_is_granted_so_mvc_keeps_404_semantics() {
        // 未匹配到 handler：不在这里拒，否则 404/405 会变成 403
        assertThat(authorize(List.of(mapping(null)), token(PLATFORM_SCOPE)).isGranted()).isTrue();
    }

    @Test
    void handler_mapping_exception_falls_through_to_next_mapping() throws Exception {
        // 前一条 mapping 抛异常（如 POST 打到只支持 GET 的路径）应继续试下一条，而不是整体拒绝
        AuthorizationResult r = authorize(List.of(throwingMapping(), mapping(handlerMethodOf(new PlatformController()))),
                token(PLATFORM_SCOPE));
        assertThat(r.isGranted()).isTrue();
    }
}
