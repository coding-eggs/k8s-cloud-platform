package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.admin.ResourceContextDTO;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.k8s.K8sCluster;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 资源管理上下文级联（租户 → 集群 → 命名空间），全部来自平台 DB：
 * <ul>
 *   <li>租户：启用状态（status=1）</li>
 *   <li>集群：该租户有分配行、且集群启用</li>
 *   <li>命名空间：分配表 platform_tenant_namespace</li>
 * </ul>
 * 无任何 K8s 调用；没有分配的租户不出现在级联里（其资源无从操作）。
 */
@Service
@RequiredArgsConstructor
public class ResourceContextService {

    private final PlatformTenantMapper tenantMapper;
    private final K8sClusterMapper clusterMapper;
    private final PlatformTenantNamespaceMapper allocationMapper;

    public ResourceContextDTO build() {
        Map<String, K8sCluster> enabledClusters = clusterMapper.listAll().stream()
                .filter(c -> c.getEnabled() == 1)
                .collect(Collectors.toMap(K8sCluster::getClusterId, Function.identity()));

        List<PlatformTenantNamespace> allocations = allocationMapper.listAll();

        ResourceContextDTO ctx = new ResourceContextDTO();
        for (PlatformTenant tenant : tenantMapper.listAll()) {
            if (tenant.getStatus() == null || tenant.getStatus() != 1) {
                continue; //停用租户不进入资源管理上下文
            }
            ResourceContextDTO.TenantNode tenantNode = new ResourceContextDTO.TenantNode();
            tenantNode.setTenantId(tenant.getId());
            tenantNode.setName(tenant.getName());
            tenantNode.setStatus(tenant.getStatus() == null ? null : tenant.getStatus().intValue());

            for (PlatformTenantNamespace allocation : allocations) {
                if (!tenant.getId().equals(allocation.getTenantId())) {
                    continue;
                }
                K8sCluster cluster = enabledClusters.get(allocation.getClusterId());
                if (cluster == null) {
                    continue; //集群已停用/不存在 → 该分配暂不可操作
                }
                ResourceContextDTO.ClusterNode clusterNode = tenantNode.getClusters().stream()
                        .filter(c -> c.getClusterId().equals(cluster.getClusterId()))
                        .findFirst()
                        .orElseGet(() -> {
                            ResourceContextDTO.ClusterNode node = new ResourceContextDTO.ClusterNode();
                            node.setClusterId(cluster.getClusterId());
                            node.setClusterName(cluster.getClusterName());
                            tenantNode.getClusters().add(node);
                            return node;
                        });
                clusterNode.getNamespaces().add(allocation.getNamespace());
            }

            if (!tenantNode.getClusters().isEmpty()) {
                ctx.getTenants().add(tenantNode);
            }
        }

        //排序保证展示稳定
        ctx.getTenants().forEach(t -> t.getClusters()
                .forEach(c -> c.getNamespaces().sort(String::compareTo)));
        return ctx;
    }

}
