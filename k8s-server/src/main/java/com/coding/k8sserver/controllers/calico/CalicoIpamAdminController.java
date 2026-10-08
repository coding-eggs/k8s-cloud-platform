package com.coding.k8sserver.controllers.calico;

import com.coding.common.models.k8s.dto.IpamBlockStatDTO;
import com.coding.common.models.k8s.dto.IpamIpDetailDTO;
import com.coding.common.models.k8s.dto.PoolIpamSummaryDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.calico.CalicoIpamOperations;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import com.coding.k8sserver.components.ResourceAccessResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 集群域 - Calico IPAM 派生查询（平台侧，边界=平台已注册该集群）。全只读、admin client。
 * <p>非六端点形态的自定义 query-param 端点，不套 {@code AbstractClusterResourceController}；
 * K8s/Calico 语义全在 {@link CalicoIpamOperations}，本类只做边界校验 + 委托。
 */
@Tag(name = "集群域-Calico IPAM", description = "IPPool 派生块视图 / 空闲点查 / 下一批空闲块（平台侧）")
@RestController
@RequestMapping("/calico/ipam")
public class CalicoIpamAdminController implements AccessBoundaryAware {

    /**平台侧端点：{@code BoundaryAuthorizationManager} 要求调用方持有 PLATFORM_SCOPE，与挂载路径无关。 */
    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.PLATFORM;
    }


    private final KubernetesOperationsFactory operationsFactory;
    private final ResourceAccessResolver accessResolver;

    public CalicoIpamAdminController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        this.operationsFactory = operationsFactory;
        this.accessResolver = accessResolver;
    }

    @GetMapping("/summary")
    @Operation(summary = "池 IPAM 汇总（capacity/allocated/free/reserved/blockCount）")
    public ResponseData<PoolIpamSummaryDTO> summary(@RequestParam String clusterId,
                                                    @RequestParam String poolName) {
        accessResolver.assertClusterAccess(clusterId);
        return new ResponseData<>(ops(clusterId).poolSummary(poolName));
    }

    @GetMapping("/blocks")
    @Operation(summary = "已物化块表（可选按池 / 关键字过滤；每块 cidr/node/free 数）")
    public ResponseData<List<IpamBlockStatDTO>> blocks(@RequestParam String clusterId,
                                                       @RequestParam(required = false) String poolName,
                                                       @RequestParam(required = false) String search) {
        accessResolver.assertClusterAccess(clusterId);
        return new ResponseData<>(ops(clusterId).listBlocks(poolName, search));
    }

    @GetMapping("/is-free")
    @Operation(summary = "点查某 IP/块当前是否空闲（jump-to 定位）")
    public ResponseData<Boolean> isFree(@RequestParam String clusterId,
                                        @RequestParam String cidrOrIp) {
        accessResolver.assertClusterAccess(clusterId);
        return new ResponseData<>(ops(clusterId).isFree(cidrOrIp));
    }

    @GetMapping("/next-free-blocks")
    @Operation(summary = "下一批空闲块 CIDR（从池起点 walk、跳过已认领，分页）")
    public ResponseData<List<String>> nextFreeBlocks(@RequestParam String clusterId,
                                                     @RequestParam String poolName,
                                                     @RequestParam(defaultValue = "0") int offset,
                                                     @RequestParam(defaultValue = "20") int limit) {
        accessResolver.assertClusterAccess(clusterId);
        return new ResponseData<>(ops(clusterId).nextFreeBlocks(poolName, offset, limit));
    }

    @GetMapping("/block-ips")
    @Operation(summary = "单块 per-IP（free/reserved/allocated + pod/ns/node；未物化合成全 free）")
    public ResponseData<List<IpamIpDetailDTO>> blockIps(@RequestParam String clusterId,
                                                        @RequestParam String cidr) {
        accessResolver.assertClusterAccess(clusterId);
        return new ResponseData<>(ops(clusterId).blockIps(cidr));
    }

    private CalicoIpamOperations ops(String clusterId) {
        return operationsFactory.getCalicoIpamOperation(clusterId);
    }

}
