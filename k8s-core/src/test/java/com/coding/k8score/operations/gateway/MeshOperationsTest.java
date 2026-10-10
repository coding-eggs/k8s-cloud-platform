package com.coding.k8score.operations.gateway;

import com.coding.common.models.k8s.dto.admin.AdminMeshProbeResult;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MeshOperations 的 ambient 活探测判定。
 * <p>只剩这一段了：capability 派生（hasGatewayApi / gatewayApiVersions / hasIstio）2026-10-10 上移到
 * platform-api 的 {@code ClusterCapabilityService}，那部分用例随之搬走（见 {@code ClusterServiceTest}）。
 * 这里保留两件事：
 * <ol>
 *   <li><b>正向</b>—— {@code istio-system/ztunnel} 命中；未命中则退化到全命名空间搜索。</li>
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
    void ambient_false_and_no_throw_when_probe_unavailable() {
        AdminMeshProbeResult result = new MeshOperations(brokenClient()).ambient();

        // 探测抛错 → false（信息性降级），且不向上抛
        assertThat(result.isIstioAmbient()).isFalse();
    }

    @Test
    void ambient_true_when_ztunnel_in_istio_system() {
        KubernetesClient client = clientWith(ztunnel(), new DaemonSetListBuilder().withItems().build());

        assertThat(new MeshOperations(client).ambient().isIstioAmbient()).isTrue();
    }

    @Test
    void ambient_falls_back_to_any_namespace_ztunnel() {
        // istio-system 未命中（控制面装在别的命名空间），全命名空间搜索命中
        KubernetesClient client = clientWith(null, new DaemonSetListBuilder().withItems(ztunnel()).build());

        assertThat(new MeshOperations(client).ambient().isIstioAmbient()).isTrue();
    }

    @Test
    void ambient_false_when_no_ztunnel_anywhere() {
        KubernetesClient client = clientWith(null, new DaemonSetListBuilder().withItems().build());

        assertThat(new MeshOperations(client).ambient().isIstioAmbient()).isFalse();
    }

}
