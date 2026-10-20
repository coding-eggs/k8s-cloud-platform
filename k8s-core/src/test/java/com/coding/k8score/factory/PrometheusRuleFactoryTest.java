package com.coding.k8score.factory;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.PrometheusRuleDTO;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.k8score.operations.NamespacedOperations;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** factory 的 switch 是穷尽的 —— 漏一个 case 在运行期才炸，这条测试让它在运行期入口上立刻暴露。 */
class PrometheusRuleFactoryTest {

    private KubernetesOperationsFactory factory;

    @BeforeEach
    void setUp() {
        KubernetesClientFactory clientFactory = mock(KubernetesClientFactory.class);
        when(clientFactory.getAdminClient(anyString())).thenReturn(mock(KubernetesClient.class));
        when(clientFactory.getTenantClient(anyString(), anyString())).thenReturn(mock(KubernetesClient.class));
        factory = new KubernetesOperationsFactory(clientFactory, mock(K8sClusterMapper.class), null,
                java.time.Duration.ofHours(24));
    }

    @Test
    void factory_builds_operations_for_prometheus_rule() {
        NamespacedOperations<PrometheusRuleDTO> ops =
                factory.getAdminNamespacedOperation(ResourceType.PROMETHEUS_RULE, "c1");

        assertThat(ops).isNotNull();
        assertThat(ops.apiVersion()).isEqualTo("monitoring.coreos.com/v1");
    }

    /** 租户路径也必须构造得出来（同一份 build switch）。 */
    @Test
    void tenant_path_also_builds_it() {
        NamespacedOperations<PrometheusRuleDTO> ops =
                factory.getNamespacedOperation(ResourceType.PROMETHEUS_RULE, "c1", "t1");

        assertThat(ops).isNotNull();
    }
}
