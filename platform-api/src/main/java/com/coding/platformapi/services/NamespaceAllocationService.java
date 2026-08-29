package com.coding.platformapi.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.utils.K8sNaming;
import com.coding.common.utils.ULIDGenerator;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.mapper.k8s.PlatformRbacTemplateMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.data.models.k8s.PlatformRbacTemplate;
import com.coding.platformapi.k8s.K8sAdminClient;
import com.coding.platformapi.models.AllocationCreateRequest;
import com.coding.platformapi.models.AllocationDeleteRequest;
import com.coding.platformapi.models.AllocationQueryRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 命名空间分配：DB 先行（/resources/rolebindings 的分配表边界前提），随后 K8s 侧四步
 * （ensure ns → sync 模板 ClusterRole → ensure SA → 建 RoleBinding，全部经 k8s-server 通用端点），
 * 任一步失败回滚分配行。失败方向安全：回滚后无记录也无绑定，不会出现"有记录但没边界"的状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NamespaceAllocationService {

    private final PlatformTenantMapper tenantMapper;
    private final PlatformTenantNamespaceMapper allocationMapper;
    private final K8sClusterMapper clusterMapper;
    private final PlatformRbacTemplateMapper templateMapper;
    private final K8sProvisioningService provisioning;
    private final K8sAdminClient adminClient;

    public PlatformTenantNamespace create(AllocationCreateRequest req) {
        PlatformTenant tenant = requireEnabledTenant(req.getTenantId());
        requireEnabledCluster(req.getClusterId());
        PlatformRbacTemplate template = resolveTemplate(req.getRoleTemplateId());

        if (allocationMapper.selectByTenantClusterNs(req.getTenantId(), req.getClusterId(), req.getNamespace()) != null) {
            throw new CloudPlatformException(EnumResponseType.NAMESPACE_ALREADY_ALLOCATED);
        }
        K8sNaming.validateRawName(req.getNamespace(), "", "namespace");

        // 先写分配行：RoleBinding 端点的边界校验以分配表为前提
        PlatformTenantNamespace row = new PlatformTenantNamespace();
        row.setId(ULIDGenerator.generateULID());
        row.setTenantId(tenant.getId());
        row.setClusterId(req.getClusterId());
        row.setNamespace(req.getNamespace());
        row.setRoleTemplateId(template.getId());
        allocationMapper.insert(row);

        try {
            // K8s 侧四步（幂等；命名 / 构造规则在本侧，k8s-server 只执行单点动作）
            adminClient.ensureNamespace(req.getClusterId(), req.getNamespace());
            provisioning.syncClusterRole(req.getClusterId(),
                    K8sNaming.templateClusterRole(template.getName()), provisioning.parseRules(template.getRules()));
            adminClient.ensureTenantSa(req.getClusterId(), tenant.getServiceAccount());
            provisioning.ensureRoleBinding(req.getClusterId(), tenant.getId(), req.getNamespace(),
                    tenant.getServiceAccount(), template.getName());
        } catch (Exception e) {
            allocationMapper.deleteByPrimaryKey(row.getId()); // 回滚分配行
            throw e;
        }
        log.info("分配命名空间：租户 {} 集群 {} ns {}", tenant.getId(), req.getClusterId(), req.getNamespace());
        return row;
    }

    public List<PlatformTenantNamespace> list(AllocationQueryRequest req) {
        if (StringUtils.hasText(req.getTenantId())) {
            return allocationMapper.listByTenant(req.getTenantId());
        }
        if (StringUtils.hasText(req.getClusterId())) {
            return allocationMapper.listByCluster(req.getClusterId());
        }
        return allocationMapper.listAll();
    }

    /**
     * 取消分配：删 RoleBinding（best-effort）→ 物理删分配行。不删命名空间本身（可能还有其他租户）。
     */
    public void delete(AllocationDeleteRequest req) {
        PlatformTenantNamespace row = allocationMapper.selectByTenantClusterNs(
                req.getTenantId(), req.getClusterId(), req.getNamespace());
        if (row == null) {
            throw new CloudPlatformException(EnumResponseType.ALLOCATION_NOT_EXIST);
        }
        PlatformTenant tenant = tenantMapper.selectByPrimaryKey(req.getTenantId());
        if (tenant != null) {
            try {
                provisioning.deleteRoleBindingIfExists(req.getClusterId(), req.getTenantId(),
                        req.getNamespace(), tenant.getServiceAccount());
            } catch (Exception e) {
                log.warn("删除 RoleBinding 失败（集群 {} ns {}）：{}", req.getClusterId(), req.getNamespace(), e.getMessage());
            }
        }
        allocationMapper.deleteByPrimaryKey(row.getId());
        log.info("取消命名空间分配：租户 {} 集群 {} ns {}", req.getTenantId(), req.getClusterId(), req.getNamespace());
    }

    private PlatformRbacTemplate resolveTemplate(String roleTemplateId) {
        if (StringUtils.hasText(roleTemplateId)) {
            PlatformRbacTemplate template = templateMapper.selectByPrimaryKey(roleTemplateId);
            if (template == null) {
                throw new CloudPlatformException(EnumResponseType.RBAC_TEMPLATE_NOT_EXIST);
            }
            return template;
        }
        // 缺省使用内置模板
        return templateMapper.listAll().stream()
                .filter(t -> t.getBuiltIn() == 1)
                .findFirst()
                .orElseThrow(() -> new CloudPlatformException(EnumResponseType.RBAC_TEMPLATE_NOT_EXIST));
    }

    private PlatformTenant requireEnabledTenant(String tenantId) {
        PlatformTenant tenant = tenantMapper.selectByPrimaryKey(tenantId);
        if (tenant == null) {
            throw new CloudPlatformException(EnumResponseType.TENANT_NOT_EXIST);
        }
        if (tenant.getStatus() == null || tenant.getStatus() != 1) {
            throw new CloudPlatformException(EnumResponseType.TENANT_DISABLED);
        }
        return tenant;
    }

    private void requireEnabledCluster(String clusterId) {
        K8sCluster cluster = clusterMapper.selectByPrimaryKey(clusterId);
        if (cluster == null || cluster.getDeletedAt() != null) {
            throw new CloudPlatformException(EnumResponseType.CLUSTER_NOT_EXIST);
        }
        if (cluster.getEnabled() != 1) {
            throw new CloudPlatformException(EnumResponseType.CLUSTER_DISABLED);
        }
    }
}
