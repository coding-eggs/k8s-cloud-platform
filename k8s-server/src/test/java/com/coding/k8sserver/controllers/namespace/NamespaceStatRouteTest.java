package com.coding.k8sserver.controllers.namespace;

import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.core.NamespaceStatOperations;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.components.ResourceAccessResolver.AccessContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 路由与边界归属：两个 controller 共享 {@code /namespacestats} 前缀，必须各走各的、互不抢，且边界声明正确。
 *
 * <ul>
 *   <li>{@code POST /namespacestats/platform/get} → 落进 {@link NamespaceStatPlatformController}（PLATFORM + admin client），
 *       <b>不会</b>被 {@link NamespaceStatController} 的 {@code GET /{namespace}} 模板接走（方法不同，本测试钉住）；</li>
 *   <li>{@code GET /namespacestats/{namespace}} → 仍落进 {@link NamespaceStatController}（TENANT）；</li>
 *   <li>边界是<b>类属性</b>（按 handler 所属类读），故必须分属两类 —— 断两条 {@code accessBoundary()}。</li>
 * </ul>
 * 这条测试正是"平台侧消费者拿不到单 ns 行"那个缺口（C1/I1）的回归网：新入口一旦被改到 TENANT 类里，
 * 或模板抢走 {@code /platform/get}，这里会当场红。
 */
class NamespaceStatRouteTest {

    private final KubernetesOperationsFactory factory = mock(KubernetesOperationsFactory.class);
    private final ResourceAccessResolver resolver = mock(ResourceAccessResolver.class);
    private final NamespaceStatOperations ops = mock(NamespaceStatOperations.class);

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(
                new NamespaceStatPlatformController(factory, resolver),
                new NamespaceStatController(factory, resolver)
        ).build();
    }

    @Test
    void platform_get_hits_the_platform_controller_with_admin_client() throws Exception {
        //PLATFORM：无租户身份（tenantId=null）+ adminMode=true → admin client
        when(resolver.resolvePlatformNamespacedAccess("c1")).thenReturn(new AccessContext(null, true));
        when(factory.getNamespaceStatOperation("c1", null, true)).thenReturn(ops);
        when(ops.row("team-a")).thenReturn(NamespaceStatOperations.emptySnapshot("team-a", 1L));

        mvc().perform(post("/namespacestats/platform/get")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clusterId\":\"c1\",\"namespace\":\"team-a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stat.namespace").value("team-a"));
    }

    @Test
    void namespace_path_still_hits_the_tenant_controller() throws Exception {
        when(resolver.resolveNamespacedAccess(null, "c1", "team-a")).thenReturn(new AccessContext("t1", false));
        when(factory.getNamespaceStatOperation("c1", "t1", false)).thenReturn(ops);
        when(ops.row("team-a")).thenReturn(NamespaceStatOperations.emptySnapshot("team-a", 1L));

        mvc().perform(get("/namespacestats/team-a").param("clusterId", "c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stat.namespace").value("team-a"));
    }

    @Test
    void the_two_controllers_declare_their_own_boundaries() {
        assertThat(new NamespaceStatPlatformController(factory, resolver).accessBoundary())
                .isEqualTo(AccessBoundary.PLATFORM);
        assertThat(new NamespaceStatController(factory, resolver).accessBoundary())
                .isEqualTo(AccessBoundary.TENANT);
    }
}
