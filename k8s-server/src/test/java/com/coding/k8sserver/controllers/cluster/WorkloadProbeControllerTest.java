package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.k8s.dto.WorkloadProbeRequestDTO;
import com.coding.common.models.k8s.dto.WorkloadProbeResultDTO;
import com.coding.common.models.k8s.dto.WorkloadProbeTargetDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.core.WorkloadProbeOperations;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.ResourceAccessResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code POST /workload-probes} 的两条不可退让的性质：
 * <ol>
 *   <li><b>边界是 UPSTREAM</b> —— 门控收口后租户进 CI 页/网格页就会触发这次探测，PLATFORM 会让它 403；</li>
 *   <li><b>命名空间清单是硬约束</b> —— 本端点是唯一一条能按命名空间读工作负载的路径，
 *       放开等于给租户一个跨租户探测器（拿自己的 token 传别人的命名空间就能问出对象存在性）。</li>
 * </ol>
 */
class WorkloadProbeControllerTest {

    /** 注入两条清单，测试不受真实默认值变化影响 */
    private static final List<String> ALLOWED = List.of("kube-system", "istio-system");
    private static final List<String> ALLOWED_CRDS = List.of("gateways.gateway.networking.k8s.io");

    private static WorkloadProbeTargetDTO t(String ns, String kind, String repo) {
        WorkloadProbeTargetDTO d = new WorkloadProbeTargetDTO();
        d.setNamespace(ns);
        d.setKind(kind);
        d.setImageRepo(repo);
        return d;
    }

    private static WorkloadProbeRequestDTO req(WorkloadProbeTargetDTO... targets) {
        WorkloadProbeRequestDTO r = new WorkloadProbeRequestDTO();
        r.setClusterId("c1");
        r.setTargets(List.of(targets));
        return r;
    }

    private static WorkloadProbeController controller() {
        KubernetesOperationsFactory factory = mock(KubernetesOperationsFactory.class);
        when(factory.getWorkloadProbeOperation("c1")).thenReturn(mock(WorkloadProbeOperations.class));
        return new WorkloadProbeController(factory, mock(ResourceAccessResolver.class), ALLOWED, ALLOWED_CRDS);
    }

    @Test
    void theEndpointIsUpstreamBoundaryNotPlatform() {
        // ⭐ 租户进 CI 页会触发这次探测，PLATFORM 边界会让它 403
        assertThat(controller().accessBoundary()).isEqualTo(AccessBoundary.UPSTREAM);
    }

    @Test
    void aTargetOutsideTheConfiguredListIsRejected() {
        // ⭐ 这条端点是唯一能按命名空间读工作负载的路径。放开等于给租户一个跨租户探测器：
        //    拿自己的 token 传 namespace=milvus 就能问出别人命名空间里有什么。
        assertThatThrownBy(() -> controller().probe(req(t("milvus", "deployment", "x/y"))))
                .hasMessageContaining("milvus");
    }

    @Test
    void oneOutOfBoundsTargetRejectsTheWholeRequest() {
        // 静默跳过越界项 = 平台侧把"被拒"当成"没装"，与「探测失败不能说成没装」同一条原则
        assertThatThrownBy(() -> controller().probe(req(
                t("kube-system", "deployment", "metrics-server"),
                t("milvus", "deployment", "x/y"))))
                .hasMessageContaining("milvus");
    }

    @Test
    void aCrdOutsideTheConfiguredListIsRejected() {
        // CRD 是集群级资源，不走命名空间清单 —— 必须有自己的清单
        WorkloadProbeTargetDTO crd = t(null, "crd", null);
        crd.setName("secrets.example.io");

        assertThatThrownBy(() -> controller().probe(req(crd)))
                .hasMessageContaining("secrets.example.io");
    }

    @Test
    void anAllowedCrdPassesTheWhitelist() {
        WorkloadProbeTargetDTO crd = t(null, "crd", null);
        crd.setName("gateways.gateway.networking.k8s.io");

        // 能走到 factory（mock 的 probe 回 null，行为与本用例无关）；关键是没被白名单拦下
        assertThat(controller().probe(req(crd))).isNotNull();
    }

    @Test
    void anEmptyTargetListIsHarmless() {
        ResponseData<List<WorkloadProbeResultDTO>> r = controller().probe(req());
        assertThat(r.getData()).isEmpty();
    }
}
