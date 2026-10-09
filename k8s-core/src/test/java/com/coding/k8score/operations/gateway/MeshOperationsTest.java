package com.coding.k8score.operations.gateway;

import com.coding.common.models.k8s.dto.MeshStatusDTO;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder;
import io.fabric8.kubernetes.api.model.apps.DaemonSetList;
import io.fabric8.kubernetes.api.model.apps.DaemonSetListBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MeshOperations 的 mesh-status 判定。三段独立可测：
 * <ol>
 *   <li><b>capability 派生</b>（hasGatewayApi / gatewayApiVersions / hasIstio）—— 纯 Map 读，无 K8s 调用。</li>
 *   <li><b>ambient 正向</b>—— {@code istio-system/ztunnel} 命中；未命中则退化到全命名空间搜索。</li>
 *   <li><b>降级</b>—— ztunnel 探测抛错（集群断开 / RBAC 未覆盖）时 {@code istioAmbient=false} 且<b>不抛异常</b>。
 *       这条是本模块的"不整页崩"承诺（spec §7）。</li>
 * </ol>
 */
class MeshOperationsTest {

    private static DaemonSet ztunnel() {
        return new DaemonSetBuilder().withNewMetadata().withName("ztunnel").endMetadata().build();
    }

    /**
     * 造出 {@code client.apps().daemonSets()} 这条 fluent 链。
     * <p>不用 {@code RETURNS_DEEP_STUBS}：{@code MixedOperation.inNamespace} 的返回类型带泛型变量，
     * deep stubs 解不出来会返回 null（实测 NPE），故逐层显式 mock。
     *
     * @param named {@code istio-system/ztunnel} 的 get() 结果（null = 未命中）
     * @param anyNs {@code inAnyNamespace().list()} 的结果（null = 该分支抛错/不可用）
     */
    @SuppressWarnings("unchecked")
    private static KubernetesClient clientWith(DaemonSet named, DaemonSetList anyNs) {
        KubernetesClient client = mock(KubernetesClient.class);
        AppsAPIGroupDSL apps = mock(AppsAPIGroupDSL.class);
        MixedOperation<DaemonSet, DaemonSetList, Resource<DaemonSet>> daemonSets = mock(MixedOperation.class);
        NonNamespaceOperation<DaemonSet, DaemonSetList, Resource<DaemonSet>> scoped = mock(NonNamespaceOperation.class);
        Resource<DaemonSet> namedResource = mock(Resource.class);

        when(client.apps()).thenReturn(apps);
        when(apps.daemonSets()).thenReturn(daemonSets);
        when(daemonSets.inNamespace(anyString())).thenReturn(scoped);
        when(daemonSets.inAnyNamespace()).thenReturn(scoped);
        when(scoped.withName(anyString())).thenReturn(namedResource);
        when(namedResource.get()).thenReturn(named);
        when(scoped.list()).thenReturn(anyNs);
        return client;
    }

    /** 探测路径整体不可用：apps() 直接抛错。 */
    private static KubernetesClient brokenClient() {
        KubernetesClient client = mock(KubernetesClient.class);
        when(client.apps()).thenThrow(new RuntimeException("集群不可达"));
        return client;
    }

    @Test
    void status_derives_discovery_flags_from_capability() {
        MeshStatusDTO status = new MeshOperations(brokenClient(),
                Map.of("gateway.networking.k8s.io", List.of("v1", "v1beta1"),
                        "networking.istio.io", List.of("v1")))
                .status();

        assertThat(status.isHasGatewayApi()).isTrue();
        assertThat(status.getGatewayApiVersions()).containsExactly("v1", "v1beta1");
        assertThat(status.isHasIstio()).isTrue();   // 命中 networking.istio.io
    }

    @Test
    void status_hasIstio_also_true_for_istio_io_group() {
        MeshStatusDTO status = new MeshOperations(brokenClient(), Map.of("istio.io", List.of("v1alpha1"))).status();

        assertThat(status.isHasIstio()).isTrue();
        assertThat(status.isHasGatewayApi()).isFalse();
    }

    @Test
    void status_empty_capability_means_not_probed_not_absent() {
        MeshStatusDTO status = new MeshOperations(brokenClient(), Map.of()).status();

        assertThat(status.isHasGatewayApi()).isFalse();
        assertThat(status.getGatewayApiVersions()).isEmpty();
        assertThat(status.isHasIstio()).isFalse();
    }

    @Test
    void status_ambient_false_and_no_throw_when_probe_unavailable() {
        MeshStatusDTO status = new MeshOperations(brokenClient(),
                Map.of("networking.istio.io", List.of("v1"))).status();

        // 探测抛错 → false（信息性降级），且不向上抛
        assertThat(status.isIstioAmbient()).isFalse();
        assertThat(status.isHasIstio()).isTrue();
    }

    @Test
    void status_ambient_true_when_ztunnel_in_istio_system() {
        KubernetesClient client = clientWith(ztunnel(), new DaemonSetListBuilder().withItems().build());

        MeshStatusDTO status = new MeshOperations(client,
                Map.of("networking.istio.io", List.of("v1"), "gateway.networking.k8s.io", List.of("v1"))).status();

        assertThat(status.isIstioAmbient()).isTrue();
    }

    @Test
    void status_ambient_falls_back_to_any_namespace_ztunnel() {
        // istio-system 未命中（控制面装在别的命名空间），全命名空间搜索命中
        KubernetesClient client = clientWith(null, new DaemonSetListBuilder().withItems(ztunnel()).build());

        MeshStatusDTO status = new MeshOperations(client, Map.of("istio.io", List.of("v1"))).status();

        assertThat(status.isIstioAmbient()).isTrue();
    }

    @Test
    void status_ambient_false_when_no_ztunnel_anywhere() {
        KubernetesClient client = clientWith(null, new DaemonSetListBuilder().withItems().build());

        MeshStatusDTO status = new MeshOperations(client, Map.of("networking.istio.io", List.of("v1"))).status();

        assertThat(status.isIstioAmbient()).isFalse();
    }

    @Test
    void versionsOf_returns_group_versions_or_empty() {
        MeshOperations ops = new MeshOperations(mock(KubernetesClient.class),
                Map.of("gateway.networking.k8s.io", List.of("v1")));

        assertThat(ops.versionsOf("gateway.networking.k8s.io")).containsExactly("v1");
        assertThat(ops.versionsOf("gateway.networking.k8s.io/v1alpha2")).isEmpty();
        assertThat(ops.versionsOf(null)).isEmpty();
    }

}
