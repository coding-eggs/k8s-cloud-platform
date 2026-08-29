package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.admin.AdminAllocationRef;
import com.coding.common.models.k8s.dto.admin.AdminCleanupRequest;
import com.coding.common.utils.K8sNaming;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.platformapi.k8s.K8sAdminClient;
import com.coding.platformapi.models.TenantCreateRequest;
import com.coding.platformapi.models.TenantKeyRequest;
import com.coding.platformapi.models.TenantUpdateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 租户管理：创建时按全量矩阵在各启用集群创建 SA（tn-<service_account>）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    private final PlatformTenantMapper tenantMapper;
    private final PlatformTenantNamespaceMapper allocationMapper;
    private final K8sProvisioningService provisioning;
    private final K8sAdminClient adminClient;

    public PlatformTenant create(TenantCreateRequest req) {
        if (!StringUtils.hasText(req.getName())) {
            throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "租户名称不能为空");
        }
        K8sNaming.validateRawName(req.getServiceAccount(), K8sNaming.TENANT_SA_PREFIX, "serviceAccount");

        if (tenantMapper.selectByServiceAccount(req.getServiceAccount()) != null) {
            throw new CloudPlatformException(EnumResponseType.SERVICE_ACCOUNT_NAME_EXIST);
        }

        Date now = new Date();
        PlatformTenant tenant = new PlatformTenant();
        tenant.setId(ULIDGenerator.generateULID());
        tenant.setName(req.getName());
        tenant.setServiceAccount(req.getServiceAccount());
        tenant.setStatus(req.getStatus() != null ? req.getStatus().byteValue() : (byte) 1);
        tenant.setCreatedAt(now);
        tenant.setUpdatedAt(now);
        tenantMapper.insert(tenant);

        if (tenant.getStatus() == 1) {
            provisionTenantSas(tenant.getId());
        }
        return tenant;
    }

    public List<PlatformTenant> list() {
        return tenantMapper.listAll();
    }

    public PlatformTenant get(TenantKeyRequest req) {
        return require(req.getId());
    }

    public PlatformTenant update(TenantUpdateRequest req) {
        PlatformTenant existing = require(req.getId());
        PlatformTenant upd = new PlatformTenant();
        upd.setId(req.getId());
        upd.setName(req.getName());
        if (req.getStatus() != null) {
            if (req.getStatus() != 0 && req.getStatus() != 1) {
                throw new CloudPlatformException(EnumResponseType.BEAN_VALIDATION_EXCEPTION, "status 只能为 0 或 1");
            }
            upd.setStatus(req.getStatus().byteValue());
        }
        upd.setUpdatedAt(new Date());
        tenantMapper.updateByPrimaryKeySelective(upd);

        // 重新启用 → 补建各集群 SA（幂等）
        if (req.getStatus() != null && req.getStatus() == 1 && existing.getStatus() != null && existing.getStatus() != 1) {
            provisionTenantSas(req.getId());
        }
        return tenantMapper.selectByPrimaryKey(req.getId());
    }

    /**
     * 软删除 + best-effort 清理各集群 SA / RoleBinding（经 k8s-server，含其 client 缓存清理）
     */
    public void delete(TenantKeyRequest req) {
        PlatformTenant tenant = require(req.getId());
        Date now = new Date();
        PlatformTenant upd = new PlatformTenant();
        upd.setId(tenant.getId());
        upd.setDeletedAt(now);
        upd.setUpdatedAt(now);
        tenantMapper.updateByPrimaryKeySelective(upd);

        List<PlatformTenantNamespace> allocations = allocationMapper.listByTenant(tenant.getId());
        Set<String> saClusterIds = new HashSet<>();
        allocations.forEach(a -> saClusterIds.add(a.getClusterId()));
        provisioning.enabledClusters().forEach(c -> saClusterIds.add(c.getClusterId()));
        AdminCleanupRequest cleanup = new AdminCleanupRequest();
        cleanup.setTenantId(tenant.getId());
        cleanup.setServiceAccount(tenant.getServiceAccount());
        if (!allocations.isEmpty()) {
            cleanup.setRoleBindings(allocations.stream().map(a -> {
                AdminAllocationRef ref = new AdminAllocationRef();
                ref.setClusterId(a.getClusterId());
                ref.setNamespace(a.getNamespace());
                return ref;
            }).toList());
        }
        cleanup.setSaClusterIds(List.copyOf(saClusterIds));
        adminClient.cleanupTenant(cleanup);
        log.info("租户 {} 已删除并清理 K8s 侧对象", tenant.getId());
    }

    /**
     * 重新执行租户 SA 开通（幂等）：创建失败重试 / 集群重建后恢复
     */
    public PlatformTenant provision(TenantKeyRequest req) {
        PlatformTenant tenant = require(req.getId());
        if (tenant.getStatus() == null || tenant.getStatus() != 1) {
            throw new CloudPlatformException(EnumResponseType.TENANT_DISABLED);
        }
        provisionTenantSas(tenant.getId());
        return tenant;
    }

    private void provisionTenantSas(String tenantId) {
        PlatformTenant tenant = tenantMapper.selectByPrimaryKey(tenantId);
        for (K8sCluster cluster : provisioning.enabledClusters()) {
            try {
                adminClient.ensureTenantSa(cluster.getClusterId(), tenant.getServiceAccount());
            } catch (Exception e) {
                log.warn("集群 {} 创建租户 SA 失败：{}", cluster.getClusterId(), e.getMessage());
            }
        }
    }

    private PlatformTenant require(String id) {
        PlatformTenant tenant = tenantMapper.selectByPrimaryKey(id);
        if (tenant == null) {
            throw new CloudPlatformException(EnumResponseType.TENANT_NOT_EXIST);
        }
        return tenant;
    }
}
