package com.coding.platformapi.services;

import com.coding.common.models.constants.SystemConstant;
import com.coding.common.models.k8s.dto.ClusterRoleDTO;
import com.coding.common.models.k8s.dto.PolicyRuleDTO;
import com.coding.common.models.k8s.dto.RoleBindingDTO;
import com.coding.common.models.k8s.dto.RoleRefDTO;
import com.coding.common.models.k8s.dto.RulesPayload;
import com.coding.common.models.k8s.dto.SubjectDTO;
import com.coding.common.utils.K8sNaming;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.data.models.k8s.PlatformRbacTemplate;
import com.coding.platformapi.k8s.K8sResourceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * K8s 侧开通编排：业务规则在本侧（upsert 判断、best-effort 遍历启用集群、命名/规则构造），
 * 传输全部走 k8s client——通用资源 CRUD 经 {@link K8sResourceClient}，
 * /admin/** 特殊端点由调用方直连 {@link com.coding.platformapi.k8s.K8sAdminClient}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class K8sProvisioningService {

    private final K8sResourceClient resourceClient;
    private final K8sClusterMapper clusterMapper;
    private final JsonMapper jsonMapper;

    /**
     * 确保分配对应的 RoleBinding 存在（命名空间内，名 = 租户 SA 名；经 /resources/rolebindings 通用端点）。
     * 边界前提：分配表三元组已存在（调用方先写 DB）。
     */
    public void ensureRoleBinding(String clusterId, String tenantId, String namespace, String serviceAccount, String templateName) {
        RoleBindingDTO dto = buildRoleBinding(clusterId, tenantId, namespace, serviceAccount, templateName);
        if (resourceClient.get(dto) != null) {
            return;
        }
        resourceClient.create(dto);
    }

    /**
     * 删分配对应的 RoleBinding（不存在则跳过；经 /resources/rolebindings 通用端点）
     */
    public void deleteRoleBindingIfExists(String clusterId, String tenantId, String namespace, String serviceAccount) {
        RoleBindingDTO dto = new RoleBindingDTO();
        dto.setClusterId(clusterId);
        dto.setTenantId(tenantId);
        dto.setNamespace(namespace);
        dto.setName(K8sNaming.tenantServiceAccount(serviceAccount));
        if (resourceClient.get(dto) == null) {
            return;
        }
        resourceClient.delete(dto);
    }

    /**
     * 构造租户分配的 RoleBinding：绑模板 ClusterRole（tn-tpl-*）到租户 SA（platform-system 下 tn-*）
     */
    private RoleBindingDTO buildRoleBinding(String clusterId, String tenantId, String namespace, String serviceAccount, String templateName) {
        String rbName = K8sNaming.tenantServiceAccount(serviceAccount);
        RoleBindingDTO dto = new RoleBindingDTO();
        dto.setClusterId(clusterId);
        dto.setTenantId(tenantId);
        dto.setName(rbName);
        dto.setNamespace(namespace);

        RoleRefDTO roleRef = new RoleRefDTO();
        roleRef.setApiGroup("rbac.authorization.k8s.io");
        roleRef.setKind("ClusterRole");
        roleRef.setName(K8sNaming.templateClusterRole(templateName));
        dto.setRoleRef(roleRef);

        SubjectDTO subject = new SubjectDTO();
        subject.setApiGroup("");
        subject.setKind("ServiceAccount");
        subject.setName(rbName);
        subject.setNamespace(SystemConstant.SYSTEM_NAMESPACE);
        dto.setSubjects(List.of(subject));
        return dto;
    }

    // ==================== 跨集群动作（best-effort，失败仅告警） ====================

    /**
     * 同步模板到所有启用集群（分配时还会 ensure，这里失败不阻断保存）。
     * 业务规则在本侧：tn-tpl-* 命名 + upsert（查 → 建/覆盖），k8s-server 只执行单点 CRUD。
     */
    public void syncTemplateToAllEnabledClusters(PlatformRbacTemplate template) {
        List<PolicyRuleDTO> rules = parseRules(template.getRules());
        String crName = K8sNaming.templateClusterRole(template.getName());
        for (K8sCluster cluster : enabledClusters()) {
            try {
                syncClusterRole(cluster.getClusterId(), crName, rules);
            } catch (Exception e) {
                log.warn("同步模板 ClusterRole 到集群 {} 失败：{}", cluster.getClusterId(), e.getMessage());
            }
        }
    }

    /**
     * 单集群 upsert 一个 ClusterRole（/admin/clusterroles 通用端点）；模板同步 / 命名空间分配共用
     */
    public void syncClusterRole(String clusterId, String name, List<PolicyRuleDTO> rules) {
        ClusterRoleDTO dto = new ClusterRoleDTO();
        dto.setClusterId(clusterId);
        dto.setName(name);
        dto.setRules(rules);
        if (resourceClient.get(dto) == null) {
            resourceClient.create(dto);
        } else {
            resourceClient.update(dto);
        }
    }

    /**
     * 删除模板在各启用集群的 ClusterRole（best-effort）
     */
    public void deleteTemplateClusterRoleEverywhere(PlatformRbacTemplate template) {
        String crName = K8sNaming.templateClusterRole(template.getName());
        for (K8sCluster cluster : enabledClusters()) {
            try {
                ClusterRoleDTO dto = new ClusterRoleDTO();
                dto.setClusterId(cluster.getClusterId());
                dto.setName(crName);
                resourceClient.delete(dto);
            } catch (Exception e) {
                log.warn("删除集群 {} 的模板 ClusterRole 失败：{}", cluster.getClusterId(), e.getMessage());
            }
        }
    }

    // ==================== 平台侧数据（DB 读取保留在本服务） ====================

    public List<K8sCluster> enabledClusters() {
        return clusterMapper.listAll().stream()
                .filter(c -> c.getEnabled() == 1)
                .toList();
    }

    /**
     * 解析模板规则 JSON（展示用；K8s 侧同步时 rules 显式传给 k8s-server）
     */
    public List<PolicyRuleDTO> parseRules(String rulesJson) {
        return jsonMapper.readValue(rulesJson, RulesPayload.class).rules();
    }

}
