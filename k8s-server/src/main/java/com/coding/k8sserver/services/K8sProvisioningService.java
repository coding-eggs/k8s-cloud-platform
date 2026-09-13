package com.coding.k8sserver.services;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.constants.SystemConstant;
import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.ClusterRoleDTO;
import com.coding.common.models.k8s.dto.NamespaceDTO;
import com.coding.common.models.k8s.dto.PolicyRuleDTO;
import com.coding.common.models.k8s.dto.RoleBindingDTO;
import com.coding.common.models.k8s.dto.RulesPayload;
import com.coding.common.models.k8s.dto.ServiceAccountDTO;
import com.coding.common.models.k8s.dto.admin.AdminAllocationRef;
import com.coding.common.models.k8s.dto.admin.AdminCleanupRequest;
import com.coding.common.utils.K8sNaming;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.k8s.PlatformRbacTemplateMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.k8s.PlatformRbacTemplate;
import com.coding.k8score.factory.KubernetesClientFactory;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.ClusterOperations;
import com.coding.k8score.operations.NamespacedOperations;
import io.fabric8.kubernetes.api.model.APIGroup;
import io.fabric8.kubernetes.api.model.GroupVersionForDiscovery;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * K8s 侧开通/清理（全部走 admin client，幂等）。
 * k8s-server 是唯一接触 K8s 的组件，platform-api 通过 /admin/** HTTP 端点调用本服务；
 * 租户/模板/kubeconfig 数据一律自查库（kubeconfig 解密收敛在 KubernetesClientFactory）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class K8sProvisioningService {


    private final KubernetesClientFactory clientFactory;
    private final KubernetesOperationsFactory operationsFactory;
    private final PlatformTenantMapper tenantMapper;
    private final PlatformRbacTemplateMapper templateMapper;
    private final JsonMapper jsonMapper;

    /**
     * 连通性探测（纳管前一次性，明文 kubeconfig；临时 client，测完即关）
     *
     * @return K8s 版本（gitVersion）
     */
    public String probeKubeconfig(String kubeconfig) {
        KubernetesClient probe = null;
        try {
            Config config = Config.fromKubeconfig(kubeconfig);
            probe = new KubernetesClientBuilder().withConfig(config).build();
            return probe.getKubernetesVersion().getGitVersion();
        } catch (Exception e) {
            throw new CloudPlatformException(EnumResponseType.K8S_CONNECT_FAILED,
                    EnumResponseType.K8S_CONNECT_FAILED.getMsg() + e.getMessage());
        } finally {
            if (probe != null) {
                probe.close();
            }
        }
    }

    /**
     * 刷新集群 API 能力（运行时 discovery 快照）：先 evict admin client（保证用最新 kubeconfig 重建连接），
     * 再 getApiGroups() 探测 → {group: [version, ...]}。仅探测并返回，持久化在 platform-api 侧。
     */
    public Map<String, List<String>> refreshCapability(String clusterId) {
        clientFactory.evictAdminClient(clusterId);
        KubernetesClient client = clientFactory.getAdminClient(clusterId);
        Map<String, List<String>> capability = new LinkedHashMap<>();
        for (APIGroup group : client.getApiGroups().getGroups()) {
            List<GroupVersionForDiscovery> versions = group.getVersions();
            capability.put(group.getName(),
                    versions == null ? List.of() : versions.stream().map(GroupVersionForDiscovery::getVersion).toList());
        }
        return capability;
    }

    /**
     * 失效某集群的 admin client + 派生的全部 tenant client（kubeconfig 变更 / 禁用 / 删除后由 platform-api 调用）。
     * 清缓存不重建：下次 getAdminClient/getTenantClient 会用最新 DB 状态惰性重建。
     */
    public void evictClient(String clusterId) {
        clientFactory.evictAdminClient(clusterId);
    }

    /**
     * 集群纳管 / 数据面重建后的完整开通（幂等）：
     * platform-system + 全部启用租户 SA + 全部模板 ClusterRole
     */
    public void provisionCluster(String clusterId) {
        //创建集群管理所需的命名空间
        ensureNamespace(clusterId, SystemConstant.SYSTEM_NAMESPACE);
        for (PlatformTenant tenant : tenantMapper.listAll()) {
            if (tenant.getStatus() != null && tenant.getStatus() == 1) {
                //创建平台已有的sa
                ensureTenantSa(clusterId, tenant.getServiceAccount());
            }
        }
        for (PlatformRbacTemplate template : templateMapper.listAll()) {
            //创建平台有的clusterrole
            syncTemplateClusterRole(clusterId, template.getName(), parseRules(template.getRules()));
        }
        log.info("集群 {} K8s 侧开通完成", clusterId);
    }

    /**
     * 确保命名空间存在（不存在则创建并打 managed-by 标签）
     */
    public void ensureNamespace(String clusterId, String namespace) {
        KubernetesClient client = clientFactory.getAdminClient(clusterId);
        Namespace ns = client.namespaces().withName(namespace).get();
        if (ns == null) {
            client.namespaces().resource(new NamespaceBuilder()
                    .withNewMetadata()
                        .withName(namespace)
                        .withLabels(Map.of(KubernetesClientFactory.MANAGED_BY_LABEL, KubernetesClientFactory.MANAGED_BY_VALUE))
                    .endMetadata()
                    .build()).create();
            log.info("集群 {} 创建命名空间 {}", clusterId, namespace);
        }
    }

    /**
     * 列出集群内全部命名空间（admin client，按名字升序；返回全字段对象，展示裁剪由 platform-api 侧决定）
     */
    public List<NamespaceDTO> listNamespaces(String clusterId) {
        KubernetesClient client = clientFactory.getAdminClient(clusterId);
        return client.namespaces().list().getItems().stream()
                .map(this::toNamespaceDTO)
                .sorted(Comparator.comparing(NamespaceDTO::getName))
                .toList();
    }

    /**
     * 删除命名空间（admin client；能否删除由 platform-api 侧业务规则判定）
     */
    public void deleteNamespace(String clusterId, String namespace) {
        KubernetesClient client = clientFactory.getAdminClient(clusterId);
        client.namespaces().withName(namespace).delete();
        log.info("集群 {} 删除命名空间 {}", clusterId, namespace);
    }

    private NamespaceDTO toNamespaceDTO(Namespace ns) {
        NamespaceDTO dto = new NamespaceDTO();
        dto.setName(ns.getMetadata().getName());
        dto.setPhase(ns.getStatus() != null ? ns.getStatus().getPhase() : null);
        dto.setCreationTimestamp(ns.getMetadata().getCreationTimestamp());
        dto.setLabels(ns.getMetadata().getLabels());
        return dto;
    }

    /**
     * 确保租户 SA 存在（platform-system 下，名为 tn-<serviceAccount>）
     */
    public void ensureTenantSa(String clusterId, String serviceAccount) {
        NamespacedOperations<ServiceAccountDTO> ops = operationsFactory.getAdminNamespacedOperation(
                ResourceType.SERVICE_ACCOUNT, clusterId);
        String saName = K8sNaming.tenantServiceAccount(serviceAccount);
        if (ops.get(SystemConstant.SYSTEM_NAMESPACE, saName) == null) {
            ServiceAccountDTO sa = new ServiceAccountDTO();
            sa.setClusterId(clusterId);
            sa.setName(saName);
            sa.setNamespace(SystemConstant.SYSTEM_NAMESPACE);
            ops.create(sa);
            log.info("集群 {} 创建租户 SA {}", clusterId, saName);
        }
    }

    /**
     * 同步模板对应的 ClusterRole（不存在则创建，存在则整体覆盖为传入规则）。
     * 仅供开通 / 分配流程内部使用；单点 CRUD 走 /admin/clusterroles 通用端点。
     */
    private void syncTemplateClusterRole(String clusterId, String templateName, List<PolicyRuleDTO> rules) {
        ClusterOperations<ClusterRoleDTO> ops = operationsFactory.getClusterOperation(
                ResourceType.CLUSTER_ROLE, clusterId);
        String crName = K8sNaming.templateClusterRole(templateName);
        ClusterRoleDTO dto = new ClusterRoleDTO();
        dto.setClusterId(clusterId);
        dto.setName(crName);
        dto.setRules(rules);
        if (ops.get(crName) == null) {
            ops.create(dto);
            log.info("集群 {} 创建模板 ClusterRole {}", clusterId, crName);
        } else {
            ops.update(dto);
        }
    }

    /**
     * 删除分配对应的 RoleBinding（不删 ns）。仅供租户清理流程内部使用；
     * 单点 CRUD 走 /resources/rolebindings 通用端点。
     */
    private void deallocateNamespace(String clusterId, String namespace, String serviceAccount) {
        NamespacedOperations<RoleBindingDTO> ops = operationsFactory.getAdminNamespacedOperation(
                ResourceType.ROLE_BINDING, clusterId);
        ops.delete(namespace, K8sNaming.tenantServiceAccount(serviceAccount));
    }

    /**
     * 租户 K8s 侧批量清理（best-effort）：各分配 ns 的 RoleBinding + 指定集群的 SA，
     * 并清除对应租户 client 缓存（SA 删除后旧 token client 已不可用）
     */
    public void cleanupTenant(AdminCleanupRequest req) {
        Set<String> touchedClusters = new HashSet<>();
        if (req.getRoleBindings() != null) {
            for (AdminAllocationRef ref : req.getRoleBindings()) {
                touchedClusters.add(ref.getClusterId());
                try {
                    deallocateNamespace(ref.getClusterId(), ref.getNamespace(), req.getServiceAccount());
                } catch (Exception e) {
                    log.warn("删除集群 {} ns {} 的 RoleBinding 失败：{}", ref.getClusterId(), ref.getNamespace(), e.getMessage());
                }
            }
        }
        if (req.getSaClusterIds() != null) {
            for (String clusterId : req.getSaClusterIds()) {
                touchedClusters.add(clusterId);
                try {
                    deleteTenantSa(clusterId, req.getServiceAccount());
                } catch (Exception e) {
                    log.warn("删除集群 {} 的租户 SA 失败：{}", clusterId, e.getMessage());
                }
            }
        }
        //SA 已删，缓存里的租户 client（token）作废，按租户维度清缓存。
        //优先用请求里的 tenantId（调用方可能已软删租户行，selectByServiceAccount 会查不到）
        String tenantId = req.getTenantId();
        if (tenantId == null) {
            PlatformTenant tenant = tenantMapper.selectByServiceAccount(req.getServiceAccount());
            tenantId = tenant != null ? tenant.getId() : null;
        }
        if (tenantId != null) {
            for (String clusterId : touchedClusters) {
                clientFactory.clearCache(clusterId, tenantId);
            }
        }
    }

    private void deleteTenantSa(String clusterId, String serviceAccount) {
        NamespacedOperations<ServiceAccountDTO> ops = operationsFactory.getAdminNamespacedOperation(
                ResourceType.SERVICE_ACCOUNT, clusterId);
        ops.delete(SystemConstant.SYSTEM_NAMESPACE, K8sNaming.tenantServiceAccount(serviceAccount));
    }

    private List<PolicyRuleDTO> parseRules(String rulesJson) {
        return jsonMapper.readValue(rulesJson, RulesPayload.class).rules();
    }

}
