package com.coding.k8sserver.controllers.gateway;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.GatewayDTO;
import com.coding.common.models.k8s.dto.MeshStatusDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.gateway.MeshOperations;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import com.coding.k8sserver.components.ResourceAccessResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 集群域 - 服务网格探测（平台侧，边界=平台已注册该集群）。只读、admin client。
 * <p>非六端点形态，不套 {@code AbstractClusterResourceController}；k8s 语义全在 {@link MeshOperations}，
 * 本类只做边界校验 + 委托。
 * <p>用 {@code POST} 而非 {@code GET}：本项目既有惯例是非资源派生查询走 POST + query 参数
 * （见 {@code CalicoFormOptionController}），且结果不进任何 HTTP 缓存 —— 它在 api 侧有短 TTL 缓存，
 * 缓存策略只应有一处。
 */
@Tag(name = "集群域-服务网格探测", description = "Istio / ambient / Gateway API 能力探测（平台侧）")
@RestController
@RequestMapping("/mesh")
public class MeshController implements AccessBoundaryAware {

    /** 平台侧端点：{@code BoundaryAuthorizationManager} 要求调用方持有 PLATFORM_SCOPE，与挂载路径无关。 */
    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.PLATFORM;
    }

    private final KubernetesOperationsFactory operationsFactory;
    private final ResourceAccessResolver accessResolver;

    public MeshController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        this.operationsFactory = operationsFactory;
        this.accessResolver = accessResolver;
    }

    @PostMapping("/status")
    @Operation(summary = "服务网格状态（hasIstio / istioAmbient / hasGatewayApi / gatewayApiVersions）")
    public ResponseData<MeshStatusDTO> status(@RequestParam String clusterId) {
        accessResolver.assertClusterAccess(clusterId);
        return new ResponseData<>(ops(clusterId).status());
    }

    /**
     * 某命名空间的 Gateway 列表（平台侧读，走 admin client）。
     * <p><b>为什么需要它</b>：命名空间是平台侧资源，其 {@code istio.io/use-waypoint} 标签要在「命名空间编辑」
     * 里选一个候选 waypoint —— 而 Gateway 是租户域资源，平台管理员没有租户上下文，走不了 {@code /gateways}。
     * 本端点只做「按命名空间列出 Gateway」，<b>是否为 waypoint 的判定留在 platform-api</b>
     * （{@code GatewayService.isWaypointGateway}）——判定口径只能有一处，否则两跳迟早漂移。
     * <p>返回全量 GatewayDTO 而非窄投影（与 {@code /mesh/gatewayclass-refs} 的取舍不同）：调用方是平台管理员，
     * 本就要看 gatewayClassName，不值得为省几个字段再造一个投影类型。
     */
    @PostMapping("/gateways")
    @Operation(summary = "命名空间内 Gateway 列表（平台侧，供命名空间编辑器的 waypoint 候选）")
    public ResponseData<List<GatewayDTO>> gateways(@RequestParam String clusterId, @RequestParam String namespace) {
        accessResolver.assertClusterAccess(clusterId);
        return new ResponseData<>(namespacedOps(clusterId).list(namespace, null, null));
    }

    private MeshOperations ops(String clusterId) {
        return operationsFactory.getMeshOperation(clusterId);
    }

    private NamespacedOperations<GatewayDTO> namespacedOps(String clusterId) {
        return operationsFactory.getAdminNamespacedOperation(ResourceType.GATEWAY, clusterId);
    }

}
