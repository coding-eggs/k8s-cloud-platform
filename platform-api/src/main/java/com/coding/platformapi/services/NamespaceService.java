package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.NamespaceDTO;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.k8s.K8sAdminClient;
import com.coding.platformapi.models.NamespaceView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 命名空间管理：K8s 命名空间列表 × 分配表合并视图 + 删除未分配命名空间。
 * 数据处理 / 业务规则全在本层；k8s-server 只执行 K8s 动作（列 / 删）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NamespaceService {

    /** 平台管理标签（与 k8s-server 侧建 ns 时打的标签一致） */
    private static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
    private static final String MANAGED_BY_VALUE = "k8s-cloud-platform";

    private final K8sAdminClient adminClient;

    private final K8sClusterMapper clusterMapper;

    private final PlatformTenantNamespaceMapper allocationMapper;

    private final PlatformTenantMapper tenantMapper;

    /**
     * 集群命名空间视图：K8s 列表 + 分配信息叠加（哪个租户占用了该 ns）
     */
    public List<NamespaceView> list(String clusterId) {
        requireCluster(clusterId);
        Map<String, String> allocatedTenant = new HashMap<>();
        for (PlatformTenantNamespace tenantNamespace : allocationMapper.listByCluster(clusterId)) {
            PlatformTenant t = tenantMapper.selectByPrimaryKey(tenantNamespace.getTenantId());
            allocatedTenant.putIfAbsent(tenantNamespace.getNamespace(), t != null ? t.getName() : tenantNamespace.getTenantId());
        }
        return adminClient.listNamespaces(clusterId).stream().map(ns -> {
            NamespaceView v = new NamespaceView();
            v.setName(ns.getName());
            v.setPhase(ns.getPhase());
            v.setCreationTimestamp(ns.getCreationTimestamp());
            v.setManagedBy(isManaged(ns.getLabels()));
            v.setAllocatedTenantName(allocatedTenant.get(ns.getName()));
            return v;
        }).toList();
    }

    /**
     * 删除命名空间：仅当平台管理（managed-by 标签）且未分配给任何租户
     */
    public void delete(String clusterId, String namespace) {
        requireCluster(clusterId);
        NamespaceDTO ns = adminClient.listNamespaces(clusterId).stream()
                .filter(n -> n.getName().equals(namespace))
                .findFirst()
                .orElseThrow(() -> new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST, "命名空间不存在: " + namespace));
        if (!isManaged(ns.getLabels())) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "仅平台创建的命名空间（带 managed-by 标签）可删除");
        }
        boolean allocated = allocationMapper.listByCluster(clusterId).stream()
                .anyMatch(a -> a.getNamespace().equals(namespace));
        if (allocated) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "该命名空间仍分配给租户，请先取消分配再删除");
        }
        adminClient.deleteNamespace(clusterId, namespace);
        log.info("集群 {} 删除命名空间 {}", clusterId, namespace);
    }

    private boolean isManaged(Map<String, String> labels) {
        return labels != null && MANAGED_BY_VALUE.equals(labels.get(MANAGED_BY_LABEL));
    }

    private void requireCluster(String clusterId) {
        K8sCluster cluster = clusterMapper.selectByPrimaryKey(clusterId);
        if (cluster == null || cluster.getDeletedAt() != null) {
            throw new CloudPlatformException(EnumResponseType.CLUSTER_NOT_EXIST);
        }
    }
}
