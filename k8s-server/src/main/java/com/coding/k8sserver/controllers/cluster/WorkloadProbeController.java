package com.coding.k8sserver.controllers.cluster;

import com.coding.common.constants.ProbeCrds;
import com.coding.common.constants.ProbeNamespaces;
import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.WorkloadProbeRequestDTO;
import com.coding.common.models.k8s.dto.WorkloadProbeResultDTO;
import com.coding.common.models.k8s.dto.WorkloadProbeTargetDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import com.coding.k8sserver.components.ResourceAccessResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 集群域 - 工作负载探测窄投影（{@code POST /workload-probes}）。
 * 消费方是 platform-api 的组件清单 —— 它要判"这个集群装了没有 / 什么版本 / 健康吗"。
 *
 * <h2>为什么单开一个类（而不是往 ClusterAdminController 加方法）</h2>
 * {@code BoundaryAuthorizationManager} 读的是 <b>handler 所属类</b>声明的 {@link AccessBoundary}。
 * 往 PLATFORM 类上加方法，新方法一样要求调用方持有 {@code PLATFORM_SCOPE} —— 而本端点
 * <b>租户必须能到</b>：门控收口之后，租户进 CI 页 / 网格页就会触发这次探测，
 * 否则冷缓存时只能对他答"不知道"，而"能不能跑 CI"正是他要的答案。
 * 故单开一个类声明 {@code UPSTREAM}，与 {@link StorageClassProvisionerController} /
 * {@link NodeAddressController} 完全同形。
 *
 * <h2>边界：UPSTREAM + 命名空间清单（本类自己兜住的那一半）</h2>
 * 本层不做租户边界判定，收窄在上游 platform-api（各自功能页的权限码）。
 * 但<b>命名空间清单必须在本层</b>：它是唯一一条能按命名空间读工作负载的路径，
 * 裸着放开的后果是租户拿自己的 token 传别人的命名空间就能问出"那里有没有叫 X 的 Deployment"。
 * 清单之外的候选 → <b>整请求 400</b>，不静默跳过（静默跳过会让平台侧把"被拒"当成"没装"）。
 *
 * <p><b>已知并显式接受的残留</b>：持任意已认证 token 直连本端点，可读到清单内命名空间的
 * 工作负载名称、镜像与副本数。实际新增面很窄 —— 这类集群上那些命名空间本来就分配给租户，
 * 该租户今天用 {@code /workloads/list} 就能读到同样内容。与 PV / 节点 IP / StorageClass
 * 三条同形，闭合它需要"只接受 platform-api 调用"的内部凭证。
 */
@Tag(name = "集群域-工作负载探测", description = "按候选读工作负载状态（组件清单用，UPSTREAM 边界）")
@RestController
@RequestMapping("/workload-probes")
public class WorkloadProbeController implements AccessBoundaryAware {

    private final KubernetesOperationsFactory operationsFactory;
    private final ResourceAccessResolver accessResolver;
    private final List<String> probeNamespaces;
    private final List<String> probeCrds;

    public WorkloadProbeController(KubernetesOperationsFactory operationsFactory,
                                   ResourceAccessResolver accessResolver,
                                   @Value("${platform.k8s.probe-namespaces:" + ProbeNamespaces.DEFAULT_CSV + "}")
                                   List<String> probeNamespaces,
                                   @Value("${platform.k8s.crd-probes:" + ProbeCrds.DEFAULT_CSV + "}")
                                   List<String> probeCrds) {
        this.operationsFactory = operationsFactory;
        this.accessResolver = accessResolver;
        this.probeNamespaces = probeNamespaces;
        this.probeCrds = probeCrds;
    }

    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.UPSTREAM;
    }

    @PostMapping
    @Operation(summary = "按候选读工作负载状态",
            description = "回 found / 命中名 / 版本 / ready / desired / detail / error。"
                    + "候选的命名空间必须在 platform.k8s.probe-namespaces 清单内，否则整请求 400。")
    public ResponseData<List<WorkloadProbeResultDTO>> probe(@RequestBody WorkloadProbeRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        List<WorkloadProbeTargetDTO> targets = request.getTargets() == null ? List.of() : request.getTargets();
        for (WorkloadProbeTargetDTO t : targets) {
            // CRD 是集群级资源：走 CRD 清单，不走命名空间清单（两者是不同的边界维度）
            if ("crd".equals(t.getKind())) {
                if (!probeCrds.contains(t.getName())) {
                    throw new CloudPlatformException(EnumResponseType.ERROR,
                            "CRD " + t.getName() + " 不在允许探测的清单内");
                }
            } else if (!probeNamespaces.contains(t.getNamespace())) {
                throw new CloudPlatformException(EnumResponseType.ERROR,
                        "命名空间 " + t.getNamespace() + " 不在允许探测的清单内");
            }
        }
        if (targets.isEmpty()) {
            return new ResponseData<>(List.of());
        }
        return new ResponseData<>(operationsFactory.getWorkloadProbeOperation(request.getClusterId()).probe(targets));
    }
}
