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
import com.coding.platformapi.k8s.K8sLifecycleClient;
import com.coding.platformapi.models.AllocationCreateRequest;
import com.coding.platformapi.models.AllocationDeleteRequest;
import com.coding.platformapi.models.AllocationQueryRequest;
import com.coding.platformapi.security.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 命名空间分配：DB 先行（/rolebindings 的分配表边界前提），随后 K8s 侧四步
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
    private final K8sLifecycleClient adminClient;
    private final NamespaceService namespaceService;
    private final AuthContext authContext;

    /** 分配完就把拉取凭据铺好 —— 租户拿到这个 ns 的第一刻起就该能拉自己的镜像（spec §6.6） */
    private final RegistryPullSecretService pullSecrets;

    /** 「这个命名空间已经归谁了」—— 冲突文案要指名道姓，否则管理员只能去库里翻 */
    private String describeHolder(PlatformTenantNamespace row) {
        String tenantId = row.getTenantId();
        PlatformTenant t = tenantId == null ? null : tenantMapper.selectByPrimaryKey(tenantId);
        String name = t != null && t.getName() != null ? t.getName() : tenantId;
        return "租户「" + name + "」—— 一个命名空间只能归属一个租户，请先取消那边的分配";
    }

    public PlatformTenantNamespace create(AllocationCreateRequest req) {
        PlatformTenant tenant = requireEnabledTenant(req.getTenantId());
        requireEnabledCluster(req.getClusterId());
        PlatformRbacTemplate template = resolveTemplate(req.getRoleTemplateId());

        // 一个命名空间只能归属一个租户（spec 2026-10-31 §3.2）。原来这里只挡"同一租户重复分配"，
        // 唯一键 V2026_10_31_3 才是真正的兜底 —— 但它要手工重放，所以代码里也先判一次：
        // 否则用户撞上的是 SQL 的 Duplicate entry，既看不懂、也定位不到是哪一步。
        List<PlatformTenantNamespace> holders =
                allocationMapper.selectByClusterAndNs(req.getClusterId(), req.getNamespace());
        if (!holders.isEmpty()) {
            throw new CloudPlatformException(EnumResponseType.NAMESPACE_ALREADY_ALLOCATED,
                    "命名空间「" + req.getNamespace() + "」已分配给" + describeHolder(holders.get(0)));
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
            // 分配出来的命名空间同样要有默认 LimitRange（Operator 侧车不带资源，配额会挡住它们）
            namespaceService.ensureDefaultLimitRange(req.getClusterId(), req.getNamespace());
            provisioning.syncClusterRole(req.getClusterId(),
                    K8sNaming.templateClusterRole(template.getName()), provisioning.parseRules(template.getRules()));
            adminClient.ensureTenantSa(req.getClusterId(), tenant.getServiceAccount());
            provisioning.ensureRoleBinding(req.getClusterId(), tenant.getId(), req.getNamespace(),
                    tenant.getServiceAccount(), template.getName());
            // 默认拉取凭据：best-effort，失败只告警（没有 Harbor / 凭据还没建好时也走到这里）
            pullSecrets.ensureQuietly(req.getClusterId(), tenant.getId(), req.getNamespace());
        } catch (Exception e) {
            allocationMapper.deleteByPrimaryKey(row.getId()); // 回滚分配行
            throw e;
        }
        log.info("分配命名空间：租户 {} 集群 {} ns {}", tenant.getId(), req.getClusterId(), req.getNamespace());
        return row;
    }

    /**
     * 分配列表。租户边界（不变量 5）：token 带 tenantInfo（自管身份，含仅持 tenant:overview:view
     * 经 perm_alloc_list_self 进入本端点的成员）时，强制查询 token 租户——入参 tenantId 一律被覆盖，
     * 不得列其他租户。无租户 claim 的 admin/base token（代管）保持原过滤语义。
     */
    public List<PlatformTenantNamespace> list(AllocationQueryRequest req) {
        var info = authContext.current();
        String tokenTenantId = (info != null && info.getTenantInfo() != null) ? info.getTenantInfo().getTenantId() : null;
        if (StringUtils.hasText(tokenTenantId)) {
            return allocationMapper.listByTenant(tokenTenantId);
        }
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
