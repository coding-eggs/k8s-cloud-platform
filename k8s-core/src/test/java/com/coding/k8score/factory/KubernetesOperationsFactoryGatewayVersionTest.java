package com.coding.k8score.factory;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.models.k8s.ResourceType;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Gateway API 的<b>版本分派</b>（B6 Phase 2）。L4 路由（TCP/TLS/UDP）的 CRD 版本按集群 capability 选：
 * v1.6 起 GA 于 {@code v1}，更早的集群只有 {@code v1alpha2}。GRPCRoute 则单挂 v1（pre-v1.1 的 v1alpha2
 * 结构不同，回退会写错对象）。
 *
 * <p>这里走 {@code getAdminNamespacedOperation} 真实入口，断言选出来的 operations 的 apiVersion ——
 * 即端到端验证"capability → CRD 版本"这条链，而不是只测一个纯函数。
 */
class KubernetesOperationsFactoryGatewayVersionTest {

    private K8sClusterMapper mapper;
    private KubernetesOperationsFactory factory;

    @BeforeEach
    void setUp() {
        mapper = mock(K8sClusterMapper.class);
        KubernetesClientFactory clientFactory = mock(KubernetesClientFactory.class);
        when(clientFactory.getAdminClient(anyString())).thenReturn(mock(KubernetesClient.class));
        when(clientFactory.getTenantClient(anyString(), anyString())).thenReturn(mock(KubernetesClient.class));
        factory = new KubernetesOperationsFactory(clientFactory, mapper);
    }

    /** 造出该集群的 capability 快照（group → versions[]） */
    private void capability(String gatewayApiVersionsJson) {
        K8sCluster cluster = new K8sCluster();
        cluster.setCapability(gatewayApiVersionsJson);
        when(mapper.selectByPrimaryKey("c1")).thenReturn(cluster);
    }

    private String apiVersionOf(ResourceType type) {
        return factory.getAdminNamespacedOperation(type, "c1").apiVersion();
    }

    // ---------- L4 路由（TCP / TLS / UDP）----------

    @Test
    void l4_uses_v1_when_cluster_serves_it() {
        capability("{\"gateway.networking.k8s.io\":[\"v1\",\"v1beta1\",\"v1alpha2\"]}");

        assertThat(apiVersionOf(ResourceType.TCP_ROUTE)).isEqualTo("gateway.networking.k8s.io/v1");
        assertThat(apiVersionOf(ResourceType.TLS_ROUTE)).isEqualTo("gateway.networking.k8s.io/v1");
        assertThat(apiVersionOf(ResourceType.UDP_ROUTE)).isEqualTo("gateway.networking.k8s.io/v1");
    }

    @Test
    void l4_falls_back_to_v1alpha2_when_v1_is_absent() {
        // 模拟 Gateway API < v1.6 的集群：L4 只有实验版本
        capability("{\"gateway.networking.k8s.io\":[\"v1beta1\",\"v1alpha2\"]}");

        assertThat(apiVersionOf(ResourceType.TCP_ROUTE)).isEqualTo("gateway.networking.k8s.io/v1alpha2");
        assertThat(apiVersionOf(ResourceType.TLS_ROUTE)).isEqualTo("gateway.networking.k8s.io/v1alpha2");
        assertThat(apiVersionOf(ResourceType.UDP_ROUTE)).isEqualTo("gateway.networking.k8s.io/v1alpha2");
    }

    @Test
    void l4_defaults_to_v1_when_capability_is_not_probed() {
        capability(null); // 未探测：不猜，按 v1 试（不存在时 apiserver 404，由上层异常透出）

        assertThat(apiVersionOf(ResourceType.TCP_ROUTE)).isEqualTo("gateway.networking.k8s.io/v1");
    }

    @Test
    void l4_defaults_to_v1_when_cluster_row_is_missing() {
        when(mapper.selectByPrimaryKey("c1")).thenReturn(null);

        assertThat(apiVersionOf(ResourceType.UDP_ROUTE)).isEqualTo("gateway.networking.k8s.io/v1");
    }

    @Test
    void l4_throws_when_capability_is_known_but_has_neither_version() {
        capability("{\"gateway.networking.k8s.io\":[\"v1beta1\"]}");

        assertThatThrownBy(() -> apiVersionOf(ResourceType.TCP_ROUTE))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("既无 v1 也无 v1alpha2");
    }

    @Test
    void l4_ignores_unparseable_capability_and_defaults_to_v1() {
        capability("{not-json");

        assertThat(apiVersionOf(ResourceType.TLS_ROUTE)).isEqualTo("gateway.networking.k8s.io/v1");
    }

    // ---------- GRPCRoute（单挂 v1）----------

    @Test
    void grpcroute_uses_v1_when_served() {
        capability("{\"gateway.networking.k8s.io\":[\"v1\",\"v1alpha2\"]}");

        assertThat(apiVersionOf(ResourceType.GRPC_ROUTE)).isEqualTo("gateway.networking.k8s.io/v1");
    }

    @Test
    void grpcroute_throws_when_v1_is_absent_instead_of_falling_back() {
        // pre-v1.1 的 v1alpha2 GRPCRoute 结构与 v1 不同（有 queryParams、filter 类型也不同）→ 必须显式报错
        capability("{\"gateway.networking.k8s.io\":[\"v1beta1\",\"v1alpha2\"]}");

        assertThatThrownBy(() -> apiVersionOf(ResourceType.GRPC_ROUTE))
                .isInstanceOf(CloudPlatformException.class)
                .hasMessageContaining("未提供 v1 版本");
    }

    @Test
    void grpcroute_defaults_to_v1_when_capability_is_not_probed() {
        capability(null);

        assertThat(apiVersionOf(ResourceType.GRPC_ROUTE)).isEqualTo("gateway.networking.k8s.io/v1");
    }

    // ---------- Phase 1 三类不受影响（单版本 v1）----------

    @Test
    void phase1_resources_are_always_v1() {
        capability("{\"gateway.networking.k8s.io\":[\"v1beta1\",\"v1alpha2\"]}");

        assertThat(apiVersionOf(ResourceType.HTTP_ROUTE)).isEqualTo("gateway.networking.k8s.io/v1");
        assertThat(apiVersionOf(ResourceType.GATEWAY)).isEqualTo("gateway.networking.k8s.io/v1");
        assertThat(factory.getClusterOperation(ResourceType.GATEWAY_CLASS, "c1").apiVersion())
                .isEqualTo("gateway.networking.k8s.io/v1");
    }

}
