package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.NamespaceDTO;
import com.coding.common.utils.K8sNaming;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.k8s.K8sResourceClient;
import com.coding.platformapi.models.NamespaceUpsertRequest;
import com.coding.platformapi.models.NamespaceView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 命名空间管理：K8s 命名空间（一等公民资源 {@code /admin/namespaces}）× 分配表合并视图 + 增删改查。
 * 数据处理 / 业务规则全在本层；k8s-server 只执行 K8s 动作（列 / 取 / 建 / 改 / 删 / yaml）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NamespaceService {

    /** 平台管理标签（managed-by 由 k8s-server 侧 converter 盖章，本层只读判定） */
    private static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
    private static final String MANAGED_BY_VALUE = "k8s-cloud-platform";

    private final K8sResourceClient k8s;

    private final K8sClusterMapper clusterMapper;

    private final PlatformTenantNamespaceMapper allocationMapper;

    private final PlatformTenantMapper tenantMapper;

    /**
     * 集群命名空间视图：K8s 列表 + 分配信息叠加（哪个租户占用了该 ns），按名字升序。
     */
    public List<NamespaceView> list(String clusterId) {
        requireCluster(clusterId);
        Map<String, String> allocatedTenant = allocatedTenant(clusterId);
        return k8s.list(query(clusterId)).stream()
                .sorted(Comparator.comparing(NamespaceDTO::getName))
                .map(ns -> toView(ns, allocatedTenant))
                .toList();
    }

    /**
     * 单个命名空间视图：K8s 取 + 分配信息叠加（与 list 同加工）。
     */
    public NamespaceView get(String clusterId, String namespace) {
        requireCluster(clusterId);
        NamespaceDTO ns = k8s.get(dto(clusterId, namespace));
        if (ns == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST, "命名空间不存在: " + namespace);
        }
        return toView(ns, allocatedTenant(clusterId));
    }

    /**
     * 命名空间 YAML（只读展示）。
     */
    public String yaml(String clusterId, String namespace) {
        requireCluster(clusterId);
        return k8s.yaml(dto(clusterId, namespace));
    }

    /**
     * 创建命名空间：名字合法 + 不存在 → k8s create（managed-by 由 converter 盖章，本层不打）。
     */
    public NamespaceDTO create(NamespaceUpsertRequest req) {
        requireCluster(req.getClusterId());
        K8sNaming.validateRawName(req.getName(), "", "命名空间");
        if (k8s.get(dto(req.getClusterId(), req.getName())) != null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_EXIST,
                    "命名空间「" + req.getName() + "」已存在");
        }
        NamespaceDTO dto = toDto(req);
        NamespaceDTO created = k8s.create(dto);
        log.info("集群 {} 创建命名空间 {}", req.getClusterId(), req.getName());
        return created;
    }

    /**
     * 更新命名空间（描述/标签）：仅平台管理（managed-by 标签）可编辑。
     */
    public NamespaceDTO update(NamespaceUpsertRequest req) {
        requireCluster(req.getClusterId());
        NamespaceDTO live = k8s.get(dto(req.getClusterId(), req.getName()));
        if (live == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST,
                    "命名空间不存在: " + req.getName());
        }
        if (!isManaged(live.getLabels())) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION,
                    "仅平台创建的命名空间（带 managed-by 标签）可编辑");
        }
        NamespaceDTO dto = toDto(req);
        NamespaceDTO updated = k8s.update(dto);
        log.info("集群 {} 更新命名空间 {}", req.getClusterId(), req.getName());
        return updated;
    }

    /**
     * 删除命名空间：仅当平台管理（managed-by 标签）且未分配给任何租户。
     */
    public void delete(String clusterId, String namespace) {
        requireCluster(clusterId);
        NamespaceDTO ns = k8s.get(dto(clusterId, namespace));
        if (ns == null) {
            throw new CloudPlatformException(EnumResponseType.RESOURCE_NOT_EXIST, "命名空间不存在: " + namespace);
        }
        if (!isManaged(ns.getLabels())) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "仅平台创建的命名空间（带 managed-by 标签）可删除");
        }
        boolean allocated = allocationMapper.listByCluster(clusterId).stream()
                .anyMatch(a -> a.getNamespace().equals(namespace));
        if (allocated) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "该命名空间仍分配给租户，请先取消分配再删除");
        }
        k8s.delete(dto(clusterId, namespace));
        log.info("集群 {} 删除命名空间 {}", clusterId, namespace);
    }

    /** clusterId → (namespace → 租户名) 分配表叠加 */
    private Map<String, String> allocatedTenant(String clusterId) {
        Map<String, String> allocatedTenant = new HashMap<>();
        for (PlatformTenantNamespace tenantNamespace : allocationMapper.listByCluster(clusterId)) {
            PlatformTenant t = tenantMapper.selectByPrimaryKey(tenantNamespace.getTenantId());
            allocatedTenant.putIfAbsent(tenantNamespace.getNamespace(), t != null ? t.getName() : tenantNamespace.getTenantId());
        }
        return allocatedTenant;
    }

    private NamespaceView toView(NamespaceDTO ns, Map<String, String> allocatedTenant) {
        NamespaceView v = new NamespaceView();
        v.setName(ns.getName());
        v.setPhase(ns.getPhase());
        v.setCreationTimestamp(ns.getCreationTimestamp());
        v.setManagedBy(isManaged(ns.getLabels()));
        v.setAllocatedTenantName(allocatedTenant.get(ns.getName()));
        v.setDescription(ns.getDescription());
        v.setLabels(ns.getLabels());
        return v;
    }

    private NamespaceDTO query(String clusterId) {
        NamespaceDTO q = new NamespaceDTO();
        q.setClusterId(clusterId);
        return q;
    }

    private NamespaceDTO dto(String clusterId, String name) {
        NamespaceDTO d = new NamespaceDTO();
        d.setClusterId(clusterId);
        d.setName(name);
        return d;
    }

    private NamespaceDTO toDto(NamespaceUpsertRequest req) {
        NamespaceDTO dto = new NamespaceDTO();
        dto.setClusterId(req.getClusterId());
        dto.setName(req.getName());
        dto.setDescription(req.getDescription());
        dto.setLabels(req.getLabels());
        return dto;
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
