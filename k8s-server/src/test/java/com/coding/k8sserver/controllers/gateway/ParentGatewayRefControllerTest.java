package com.coding.k8sserver.controllers.gateway;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.GatewayDTO;
import com.coding.common.models.k8s.dto.ParentGatewayRefDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.components.ResourceAccessResolver.AccessContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 父网关候选端点（spec §5.3）的四条不变式：
 * <ol>
 *   <li><b>白名单写死在服务端</b>（{@code istio-system}），调用方指定不了；</li>
 *   <li><b>读取目标另有判据</b> —— 只有"与路由同级 / 白名单 / 本来就有权的 ns"可读，
 *       否则返回空列表，不是报错；</li>
 *   <li><b>投影只给下拉要的字段</b>（name/namespace/listenerNames/waypointFor）；</li>
 *   <li><b>入口必须判路由所在 ns 的边界</b> —— 换了 admin client 读，但"这个路由归不归你"仍由解析器决定。</li>
 * </ol>
 */
class ParentGatewayRefControllerTest {

    private final KubernetesOperationsFactory factory = mock(KubernetesOperationsFactory.class);
    private final ResourceAccessResolver resolver = mock(ResourceAccessResolver.class);
    private final ParentGatewayRefController controller =
            new ParentGatewayRefController(factory, resolver);

    private static final AccessContext TENANT = new AccessContext("t1", false);
    private static final AccessContext ADMIN = new AccessContext("t1", true);

    // ---------- ② 读取目标的判据 ----------

    @Test
    void theRoutesOwnNamespaceIsAlwaysReadable() {
        assertThat(controller.mayRead(TENANT, "c1", "app-a", "app-a")).isTrue();
    }

    @Test
    void thePlatformGatewayWhitelistIsAlwaysReadable() {
        assertThat(controller.mayRead(TENANT, "c1", "app-a", "istio-system")).isTrue();
    }

    @Test
    void aTenantsOtherAllocatedNamespaceIsReadable() {
        when(resolver.mayAccessNamespace("t1", "c1", "app-b")).thenReturn(true);

        assertThat(controller.mayRead(TENANT, "c1", "app-a", "app-b")).isTrue();
    }

    @Test
    void aForeignNamespaceIsNotReadable() {
        when(resolver.mayAccessNamespace("t1", "c1", "someone-else")).thenReturn(false);

        assertThat(controller.mayRead(TENANT, "c1", "app-a", "someone-else")).isFalse();
    }

    @Test
    void aPlatformAdminMayReadAnyNamespace() {
        assertThat(controller.mayRead(ADMIN, "c1", "app-a", "kube-system")).isTrue();
    }

    // ---------- ② 不可读 = 空列表，不是错误 ----------

    @Test
    void aForeignTargetYieldsAnEmptyListAndReadsNothing() {
        when(resolver.resolveNamespacedAccess(any(), any(), any())).thenReturn(TENANT);
        when(resolver.mayAccessNamespace("t1", "c1", "someone-else")).thenReturn(false);

        var resp = controller.list(null, "c1", "app-a", "someone-else");

        assertThat(resp.getData()).isEmpty();
        verifyNoInteractions(factory);
    }

    // ---------- ④ 边界 ----------

    @Test
    void theBoundaryIsCheckedForTheRoutesNamespaceBeforeAnythingIsRead() {
        when(resolver.resolveNamespacedAccess(any(), any(), any()))
                .thenThrow(new CloudPlatformException(EnumResponseType.NAMESPACE_NOT_ACCESSIBLE));

        assertThatThrownBy(() -> controller.list(null, "c1", "someone-elses-ns", null))
                .isInstanceOf(CloudPlatformException.class);
        verifyNoInteractions(factory);
    }

    // ---------- ③ 投影 ----------

    @Test
    void theProjectionCarriesOnlyWhatTheDropdownNeeds() {
        GatewayDTO g = new GatewayDTO();
        g.setName("external-gateway");
        g.setLabels(Map.of("istio.io/waypoint-for", "service"));
        // 这些字段**不该**出现在投影里（它们是给"能管这个网关的人"看的）
        g.setGatewayClassName("istio");
        g.setAddresses(List.of());

        GatewayDTO.Listener l1 = new GatewayDTO.Listener();
        l1.setName("http");
        GatewayDTO.Listener l2 = new GatewayDTO.Listener();
        l2.setName("http");            // 重名去重
        GatewayDTO.Listener l3 = new GatewayDTO.Listener();
        l3.setName("  ");              // 空白丢弃
        g.setListeners(List.of(l1, l2, l3));

        ParentGatewayRefDTO ref = ParentGatewayRefController.toRef(g, "istio-system");

        assertThat(ref.getName()).isEqualTo("external-gateway");
        assertThat(ref.getNamespace()).isEqualTo("istio-system");
        assertThat(ref.getListenerNames()).containsExactly("http");
        assertThat(ref.getWaypointFor()).isEqualTo("service");
    }

    @Test
    void aGatewayWithoutListenersOrLabelsStillProjects() {
        GatewayDTO g = new GatewayDTO();
        g.setName("plain");

        ParentGatewayRefDTO ref = ParentGatewayRefController.toRef(g, "app-a");

        assertThat(ref.getListenerNames()).isEmpty();
        assertThat(ref.getWaypointFor()).isNull();
    }
}
