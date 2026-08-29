package com.coding.k8score.factory;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.constants.SystemConstant;
import com.coding.common.utils.AESUtils;
import com.coding.common.utils.K8sNaming;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.auth.PlatformTenantNamespaceMapper;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.mapper.k8s.PlatformRbacTemplateMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformTenantNamespace;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.data.models.k8s.PlatformRbacTemplate;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.authentication.TokenRequest;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class KubernetesClientFactory {

    @Autowired
    private K8sClusterMapper k8sClusterMapper;

    @Autowired
    private PlatformTenantMapper tenantMapper;

    @Autowired
    private PlatformTenantNamespaceMapper tenantNamespaceMapper;

    @Autowired
    private PlatformRbacTemplateMapper templateMapper;

    @Value("${k8s.cloud.kubeconfig.aes-key:daXs1znnIStfQCVFyC8cvuS9OQZRTgeBJLLrrvu/hUM=}")
    private String AES_KEY;


    private final Map<String, KubernetesClient> adminClientCache = new ConcurrentHashMap<>();

    private final Map<ClientKey, KubernetesClient> tenantClientCache = new ConcurrentHashMap<>();


    @PostConstruct
    public void init () {
        List<K8sCluster> k8sClusterList = k8sClusterMapper.listAll();
        for (K8sCluster k8sCluster : k8sClusterList) {
            if (k8sCluster.getEnabled() == 1) {
                KubernetesClient adminClient = createAdminClient(k8sCluster);
                adminClientCache.put(k8sCluster.getClusterId(), adminClient);

                if (!adminClient.namespaces().withName(SystemConstant.SYSTEM_NAMESPACE).isReady()) {
                    adminClient.namespaces().resource(new NamespaceBuilder()
                                    .withNewMetadata().withName(SystemConstant.SYSTEM_NAMESPACE).endMetadata()
                            .build())
                            .create();
                }
            }
        }
        log.info("初始化所有集群admin客户端缓存结束");

        //获取所有租户
        List<PlatformTenant> platformTenantList = tenantMapper.listAll();
        for (PlatformTenant platformTenant : platformTenantList) {
            if (platformTenant.getStatus() == 1) {
                // 租户可用集群由命名空间分配表驱动，一个租户可能有多个集群
                for (String clusterId : tenantMapper.listClusterIdsByTenant(platformTenant.getId())) {
                    KubernetesClient adminClient = adminClientCache.get(clusterId);
                    if (adminClient == null) {
                        log.debug("跳过租户客户端初始化：集群 {} 未启用，租户 {}", clusterId, platformTenant.getId());
                        continue;
                    }
                    createTenantClient(adminClient, new ClientKey(clusterId, platformTenant.getId()));
                }
            }
        }
        log.info("初始化所有tenant客户端缓存结束");
    }

    /**
     * 获取管理员权限的 client
     * @param clusterId 集群id
     * @return client
     */
    public KubernetesClient getAdminClient(String clusterId) {
        K8sCluster k8sCluster = k8sClusterMapper.selectByPrimaryKey(clusterId);
        if (!adminClientCache.containsKey(clusterId)) {
            adminClientCache.put(clusterId, createAdminClient(k8sCluster));
        }
        return adminClientCache.get(clusterId);
    }

    /**
     * 获取租户的client
     * @param clusterId 集群id
     * @param tenantId 租户id
     * @return client
     */
    public KubernetesClient getTenantClient(String clusterId, String tenantId) {
        ClientKey key = new ClientKey(clusterId, tenantId);
        if (!tenantClientCache.containsKey(key)) {
            tenantClientCache.put(key, createTenantClient(getAdminClient(clusterId), key));
        }
        return tenantClientCache.get(key);
    }


    /**
     * =========================
     * 创建 tenant 级 client
     * =========================
     */
    private KubernetesClient createTenantClient(KubernetesClient adminClient, ClientKey key) {

        String clusterId = key.clusterId();
        String tenantId = key.tenantId();

        PlatformTenant tenant = tenantMapper.selectByPrimaryKey(tenantId);
        if (tenant == null || tenant.getStatus() != 1) {
            throw new CloudPlatformException(EnumResponseType.TENANT_NOT_EXIST);
        }
        if (!tenantMapper.hasClusterAccess(tenantId, clusterId)) {
            throw new CloudPlatformException(EnumResponseType.NAMESPACE_NOT_ACCESSIBLE);
        }

        if(!adminClient.serviceAccounts().inNamespace(SystemConstant.SYSTEM_NAMESPACE)
                .withName(K8sNaming.tenantServiceAccount(tenant.getServiceAccount()))
                .isReady()) {
            adminClient.serviceAccounts().resource(new ServiceAccountBuilder()
                            .withNewMetadata()
                            .withName(K8sNaming.tenantServiceAccount(tenant.getServiceAccount()))
                            .withNamespace(SystemConstant.SYSTEM_NAMESPACE)
                            .endMetadata()
                            .build())
                    .create();
        }

        // 自愈：该租户在本集群各分配命名空间的 RoleBinding 缺失则补建（best-effort）
        ensureRoleBindings(adminClient, clusterId, tenant);

        TokenRequest tokenRequest = adminClient.serviceAccounts()
                .inNamespace(SystemConstant.SYSTEM_NAMESPACE)
                .withName(K8sNaming.tenantServiceAccount(tenant.getServiceAccount()))
                .tokenRequest();

        if (tokenRequest == null) {
            throw new CloudPlatformException(EnumResponseType.SERVICE_ACCOUNT_NOT_EXIST);
        }
        String token = tokenRequest.getStatus().getToken();
        Config config = new ConfigBuilder(adminClient.getConfiguration())
                .withOauthToken(token).build();
        return new KubernetesClientBuilder()
                .withConfig(config)
                .build();

    }


    /**
     * 补全租户在本集群各分配命名空间的 RoleBinding（自愈：缺失则建，best-effort，单个失败仅告警不阻断）。
     * 命名与结构和管理侧分配流程一致：名 = tn-<sa>（ns 内），roleRef → 模板 ClusterRole（tn-tpl-*），
     * subject → platform-system 下的租户 SA。该集群无分配的租户直接跳过（SA 惰性存在即可）。
     */
    private void ensureRoleBindings(KubernetesClient adminClient, String clusterId, PlatformTenant tenant) {
        List<PlatformTenantNamespace> allocations = tenantNamespaceMapper.listByTenant(tenant.getId()).stream()
                .filter(a -> clusterId.equals(a.getClusterId()))
                .toList();
        if (allocations.isEmpty()) {
            return;
        }
        String rbName = K8sNaming.tenantServiceAccount(tenant.getServiceAccount());
        for (PlatformTenantNamespace allocation : allocations) {
            String namespace = allocation.getNamespace();
            try {
                RoleBinding existing = adminClient.rbac().roleBindings()
                        .inNamespace(namespace).withName(rbName).get();
                if (existing != null) {
                    continue;
                }
                String templateName = resolveTemplateName(allocation.getRoleTemplateId());
                RoleBinding roleBinding = new RoleBindingBuilder()
                        .withNewMetadata()
                            .withName(rbName)
                            .withNamespace(namespace)
                        .endMetadata()
                        .withNewRoleRef()
                            .withApiGroup("rbac.authorization.k8s.io")
                            .withKind("ClusterRole")
                            .withName(K8sNaming.templateClusterRole(templateName))
                        .endRoleRef()
                        .addNewSubject()
                            .withApiGroup("")
                            .withKind("ServiceAccount")
                            .withName(rbName)
                            .withNamespace(SystemConstant.SYSTEM_NAMESPACE)
                        .endSubject()
                        .build();
                adminClient.rbac().roleBindings().resource(roleBinding).create();
                log.info("集群 {} 命名空间 {} 补建租户 RoleBinding {}", clusterId, namespace, rbName);
            } catch (Exception e) {
                log.warn("集群 {} 命名空间 {} 补建租户 RoleBinding {} 失败：{}", clusterId, namespace, rbName, e.getMessage());
            }
        }
    }

    /**
     * 分配行 → 模板名：roleTemplateId 有值取对应模板；缺省 / 引用失效回退内置模板
     * （与 platform-api 分配侧 resolveTemplate 同语义）
     */
    private String resolveTemplateName(String roleTemplateId) {
        if (StringUtils.hasText(roleTemplateId)) {
            PlatformRbacTemplate template = templateMapper.selectByPrimaryKey(roleTemplateId);
            if (template != null) {
                return template.getName();
            }
        }
        return templateMapper.listAll().stream()
                .filter(t -> t.getBuiltIn() == 1)
                .findFirst()
                .map(PlatformRbacTemplate::getName)
                .orElseThrow(() -> new CloudPlatformException(EnumResponseType.RBAC_TEMPLATE_NOT_EXIST));
    }

    /**
     * 创建管理员client
     * @return
     */
    private KubernetesClient createAdminClient(K8sCluster k8sCluster) {
        check(k8sCluster);
        String kubeconfig = AESUtils.decrypt(k8sCluster.getKubeconfig(), AES_KEY);
        Config config = Config.fromKubeconfig(kubeconfig);
        return new KubernetesClientBuilder().withConfig(config).build();
    }

    private void check (K8sCluster k8sCluster) {

        if (k8sCluster == null || !StringUtils.hasText(k8sCluster.getKubeconfig())) {
            throw new CloudPlatformException(EnumResponseType.NON_KUBE_CONFIG);
        }

        if (k8sCluster.getEnabled() != 1) {
            throw new CloudPlatformException(EnumResponseType.CLUSTER_DISABLED);
        }
    }



    /**
     * =========================
     * 清理单个缓存
     * =========================
     */
    public void clearCache(String clusterId, String tenantId) {
        ClientKey key = new ClientKey(clusterId, tenantId);

        KubernetesClient client = tenantClientCache.remove(key);
        if (client != null) {
            client.close();
            log.info("Closed client: cluster={}, tenant={}", clusterId, tenantId);
        }
    }


    /**
     * 清理所有缓存（应用关闭或重启时使用）
     */
    public void clearAllCache() {
        tenantClientCache.forEach((clusterId, client) -> {
            try {
                client.close();
            } catch (Exception ignored) {}
        });
        tenantClientCache.clear();
        log.info("已清理所有 KubernetesClient 缓存");
    }

    /**
     * cache key
     * @param clusterId 集群id
     * @param tenantId 租户id
     */
    public record ClientKey(String clusterId, String tenantId) {}


}
