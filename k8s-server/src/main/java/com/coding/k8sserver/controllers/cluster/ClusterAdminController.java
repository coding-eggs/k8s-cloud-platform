package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.k8s.dto.admin.AdminClusterKeyRequest;
import com.coding.common.models.k8s.dto.admin.AdminProbeRequest;
import com.coding.common.models.k8s.dto.admin.AdminProbeResult;
import com.coding.common.models.k8s.dto.admin.AdminProvisionRequest;
import com.coding.common.models.system.ResponseData;
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
 * 平台管理域 - 集群生命周期（/admin/cluster/**）：platform-api 业务编排的 K8s 侧执行入口。
 * 全部走 admin client；权限由 SecurityFilterChain 统一要求 PLATFORM:admin（不透传租户上下文）。
 */
@Tag(name = "平台管理域-集群", description = "集群连通性探测 / 集群开通 / API 能力刷新")
@RestController
@RequestMapping("/admin/cluster")
@RequiredArgsConstructor
public class ClusterAdminController {

    private final K8sProvisioningService provisioning;

    @PostMapping("/probe")
    @Operation(summary = "集群连通性探测", description = "纳管前一次性探测，明文 kubeconfig，临时 client 测完即关")
    public ResponseData<AdminProbeResult> probe(@RequestBody AdminProbeRequest req) {
        AdminProbeResult result = new AdminProbeResult();
        result.setVersion(provisioning.probeKubeconfig(req.getKubeconfig()));
        return new ResponseData<>(result);
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
