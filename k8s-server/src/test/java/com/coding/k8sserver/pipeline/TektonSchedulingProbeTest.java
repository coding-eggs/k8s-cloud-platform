package com.coding.k8sserver.pipeline;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.ConfigMapDTO;
import com.coding.common.models.pipeline.TektonSchedulingDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 探 Tekton 的 {@code coschedule}。
 *
 * <p>⚠️ 它必须用 **admin 凭据**读（配置在 {@code tekton-pipelines}，租户 SA 读不到）——
 * 本测试盯的就是"走了 admin 那条路"以及"读不到时的保守行为"。
 */
class TektonSchedulingProbeTest {

    private final KubernetesOperationsFactory factory = mock(KubernetesOperationsFactory.class);
    private final TektonSchedulingProbe probe = new TektonSchedulingProbe(factory);

    /** `getAdminNamespacedOperation` 是 `<T> NamespacedOperations<T>`，泛型推断在 when() 里过不去 —— 用 doReturn。 */
    @SuppressWarnings("unchecked")
    private NamespacedOperations<ConfigMapDTO> opsReturning(ConfigMapDTO cm) {
        NamespacedOperations<ConfigMapDTO> ops = mock(NamespacedOperations.class);
        when(ops.get(anyString(), anyString())).thenReturn(cm);
        doReturn(ops).when(factory).getAdminNamespacedOperation(any(ResourceType.class), anyString());
        return ops;
    }

    private static ConfigMapDTO featureFlags(String coschedule) {
        ConfigMapDTO cm = new ConfigMapDTO();
        cm.setData(coschedule == null ? new java.util.LinkedHashMap<>() : Map.of("coschedule", coschedule));
        return cm;
    }

    @Test
    void itReadsTheCoscheduleWithTheAdminClient() {
        NamespacedOperations<ConfigMapDTO> ops = opsReturning(featureFlags("pipelineruns"));

        TektonSchedulingDTO dto = probe.of("c1");

        assertThat(dto.coschedule()).isEqualTo("pipelineruns");
        assertThat(dto.multiPvcWorkspacesAllowed()).isTrue();
        // 读的是那个 ConfigMap（不是别的命名空间/别的对象）
        verify(ops).get("tekton-pipelines", "feature-flags");
    }

    @Test
    void aClusterOnTheDefaultWorkspacesSaysNo() {
        opsReturning(featureFlags("workspaces"));

        assertThat(probe.allowsMultiplePvcWorkspaces("c1")).isFalse();
    }

    @Test
    void aMissingConfigMapMeansTektonUsesItsDefault() {
        // 对象不在（比如 Tekton 装在别的命名空间）→ 退回默认值 workspaces → 不支持。
        // 这是**对的**，不是"读失败"：默认就是不允许两块 PVC workspace
        opsReturning(null);

        TektonSchedulingDTO dto = probe.of("c1");

        assertThat(dto.coschedule()).isEqualTo("workspaces");
        assertThat(dto.multiPvcWorkspacesAllowed()).isFalse();
    }

    @Test
    void aReadFailureDegradesTheCacheInsteadOfFailingRuns() {
        // ⭐ fail-closed 的方向：读不到就说"不支持" —— 退化掉缓存只是慢，
        //    而在真不支持的集群上挂两块 PVC workspace 会让**运行直接判失败**
        doThrow(new RuntimeException("forbidden"))
                .when(factory).getAdminNamespacedOperation(any(ResourceType.class), anyString());

        assertThat(probe.allowsMultiplePvcWorkspaces("c1")).isFalse();
    }

    @Test
    void theAnswerIsCachedPerCluster() {
        // 创建运行的路径**每条运行都要问它一次** —— 不缓存等于每条运行白加一次集群读
        NamespacedOperations<ConfigMapDTO> ops = opsReturning(featureFlags("pipelineruns"));

        probe.of("c1");
        probe.of("c1");

        verify(ops, org.mockito.Mockito.times(1)).get(anyString(), anyString());
    }
}
