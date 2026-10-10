package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.k8s.dto.ClusterAggregateDTO;
import com.coding.common.models.k8s.dto.admin.AdminClusterKeyRequest;
import com.coding.common.models.k8s.dto.admin.AdminProbeRequest;
import com.coding.common.models.k8s.dto.admin.AdminProbeResult;
import com.coding.common.models.k8s.dto.admin.AdminProvisionRequest;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.services.K8sProvisioningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 平台管理域 - 集群生命周期（/cluster/**）：platform-api 业务编排的 K8s 侧执行入口。
 * 全部走 admin client；本类声明 AccessBoundary.PLATFORM：BoundaryAuthorizationManager 要求调用方持有 PLATFORM_SCOPE（不透传租户上下文）。
 */
@Tag(name = "平台管理域-集群", description = "集群连通性探测 / 集群开通 / API 能力刷新")
@RestController
@RequestMapping("/cluster")
@RequiredArgsConstructor
public class ClusterAdminController implements AccessBoundaryAware {

    /**平台侧端点：{@code BoundaryAuthorizationManager} 要求调用方持有 PLATFORM_SCOPE，与挂载路径无关。 */
    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.PLATFORM;
    }


    private final K8sProvisioningService provisioning;
    private final KubernetesOperationsFactory operationsFactory;
    private final ResourceAccessResolver accessResolver;

    @PostMapping("/probe")
    @Operation(summary = "集群连通性探测", description = "纳管前一次性探测，明文 kubeconfig，临时 client 测完即关")
    public ResponseData<AdminProbeResult> probe(@RequestBody AdminProbeRequest req) {
        AdminProbeResult result = new AdminProbeResult();
        result.setVersion(provisioning.probeKubeconfig(req.getKubeconfig()));
        return new ResponseData<>(result);
    }

    @PostMapping("/resource-aggregate")
    @Operation(summary = "集群资源聚合快照",
            description = "跨命名空间一次 pass（固定 9 次 list，与命名空间数量无关）产出：总量 + per-namespace 明细 + "
                    + "异常 Pod + 节点健康 + 存储统计。K8s 语义全在 ClusterAggregationOperations，本端点只做边界校验 + 透传")
    public ResponseData<ClusterAggregateDTO> resourceAggregate(@RequestBody AdminClusterKeyRequest req) {
        accessResolver.assertClusterAccess(req.getClusterId());
        return new ResponseData<>(operationsFactory.getClusterAggregationOperation(req.getClusterId()).aggregate());
    }

    @PostMapping("/provision")
    @Operation(summary = "集群开通（幂等）", description = "platform-system + 全部启用租户 SA + 全部模板 ClusterRole")
    public ResponseData<Void> provision(@RequestBody AdminProvisionRequest req) {
        provisioning.provisionCluster(req.getClusterId());
        return new ResponseData<>();
    }

    @PostMapping("/capability/refresh")
    @Operation(summary = "刷新集群 API 能力", description = "evict admin client 后 getApiGroups() 探测，返回 group→versions（持久化在 platform-api 侧）")
    public ResponseData<Map<String, List<String>>> refreshCapability(@RequestBody AdminClusterKeyRequest req) {
        return new ResponseData<>(provisioning.refreshCapability(req.getClusterId()));
    }

    @PostMapping("/client/evict")
    @Operation(summary = "失效集群 client 缓存", description = "kubeconfig 变更 / 禁用 / 删除后调用，清除 admin + 派生 tenant client（不重建，下次访问惰性重建）")
    public ResponseData<Void> evictClient(@RequestBody AdminClusterKeyRequest req) {
        provisioning.evictClient(req.getClusterId());
        return new ResponseData<>();
    }

}
