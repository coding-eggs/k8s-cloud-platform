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

    public static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
    public static final String MANAGED_BY_VALUE = "k8s-cloud-platform";


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


    /**
     * 启动时探活一次：遍历启用的集群，可用则补全 {@code platform-system}，
     * 可用/不可用都把结果写回 {@code k8s_cluster.status}。
     * <p>这里<b>不再预建 client</b>（admin/tenant 都改为冷启动，见 {@link #getAdminClient}/{@link #getTenantClient}）——
     * 探活用临时 client，连完即关。
     * <p>逐集群独立兜异常：一个集群连不上只把它标成 DISCONNECTED，不影响其余集群和启动。
     */
    @PostConstruct
    public void init () {
        probeAllClusters();
    }

    /**
     * 探活全部启用集群（启动一次 + 定时任务周期性调用）。
     * <p>判定只看「能不能连上、{@code platform-system} 在不在」：可用 → CONNECTED，否则 DISCONNECTED。
     */
    public void probeAllClusters() {
        List<K8sCluster> clusters;
        try {
            clusters = k8sClusterMapper.listAll();
        } catch (Exception e) {
            log.warn("集群探活：取集群清单失败，跳过这一轮：{}", e.getMessage());
            return;
        }
        int available = 0;
        int unavailable = 0;
        for (K8sCluster cluster : clusters) {
            if (Integer.valueOf(1).equals(cluster.getEnabled())) {
                if (probeCluster(cluster)) {
                    available++;
                } else {
                    unavailable++;
                }
            }
        }
        log.info("集群探活完成：{} 个可用，{} 个不可用", available, unavailable);
    }

    /** @return 集群是否可用 */
    private boolean probeCluster(K8sCluster cluster) {
        try (KubernetesClient client = createAdminClient(cluster)) {
            // 临时 client：探完即关，不放进缓存（缓存只由 get*Client 冷启动时填充）
            if (!client.namespaces().withName(SystemConstant.SYSTEM_NAMESPACE).isReady()) {
                client.namespaces().resource(new NamespaceBuilder()
                                .withNewMetadata()
                                .withName(SystemConstant.SYSTEM_NAMESPACE)
                                .withLabels(Map.of(MANAGED_BY_LABEL, MANAGED_BY_VALUE))
                                .endMetadata()
                                .build())
                        .create();
                log.info("集群 {} 补全系统命名空间 {}", cluster.getClusterId(), SystemConstant.SYSTEM_NAMESPACE);
            }
            updateStatus(cluster.getClusterId(), K8sCluster.ClusterStatus.CONNECTED);
            return true;
        } catch (Exception e) {
            log.warn("集群 {} 探活失败，标记 DISCONNECTED：{}", cluster.getClusterId(), e.getMessage());
            updateStatus(cluster.getClusterId(), K8sCluster.ClusterStatus.DISCONNECTED);
            return false;
        }
    }

    /** 回写集群状态；库写失败只告警，不能反过来影响探活与启动。 */
    private void updateStatus(String clusterId, K8sCluster.ClusterStatus status) {
        try {
            K8sCluster upd = new K8sCluster();
            upd.setClusterId(clusterId);
            upd.setStatus(status);
            k8sClusterMapper.updateByPrimaryKeySelective(upd);
        } catch (Exception e) {
            log.warn("回写集群 {} 状态为 {} 失败：{}", clusterId, status, e.getMessage());
        }
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
     * 解密后的 admin kubeconfig（S3.6：helm 需要文件而不是 fabric8 client）。
     * <p>⚠️ 调用方必须立即使用并丢弃，不得写日志、不得落盘到临时目录之外。
     */
    public String adminKubeconfig(String clusterId) {
        K8sCluster k8sCluster = k8sClusterMapper.selectByPrimaryKey(clusterId);
        if (k8sCluster == null) {
            throw new CloudPlatformException(EnumResponseType.CLUSTER_NOT_EXIST);
        }
        return AESUtils.decrypt(k8sCluster.getKubeconfig(), AES_KEY);
    }

    /**
     * 获取租户的client
     * @param clusterId 集群id
     * @param tenantId 租户id
     * @return client
     */
    public KubernetesClient getTenantClient(String clusterId, String tenantId) {
        ClientKey key = new ClientKey(clusterId, tenantId);
        KubernetesClient cached = tenantClientCache.get(key);
        if (cached != null) {
            return cached;
        }
        KubernetesClient created = createTenantClient(getAdminClient(clusterId), key);
        if (created == null) {
            // 租户不存在 / 已禁用：不缓存（ConcurrentHashMap 不接受 null；租户之后可能被启用）
            return null;
        }
        tenantClientCache.put(key, created);
        return created;
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
            log.warn("租户不存在或已被禁用。集群：{}，租户：{}", clusterId, tenantId);
            return null;
        }

        //如果这个租户的sa不存在系统命名空间则创建一个，保证租户必有sa对应在各个集群
        if(!adminClient.serviceAccounts()
                .inNamespace(SystemConstant.SYSTEM_NAMESPACE)
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

        //补全rolebinding，分配命名空间其实就是创建对应权限模板的rolebingding
        ensureRoleBindings(adminClient, clusterId, tenant);

        //通过token request 创建 client
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
        //获取这个租户的命名空间列表
        List<PlatformTenantNamespace> allocations = tenantNamespaceMapper.listByTenant(tenant.getId()).stream()
                .filter(a -> clusterId.equals(a.getClusterId()))
                .toList();
        if (allocations.isEmpty()) {
            return;
        }
        //sa name 和 rolebinding同名
        String rbName = K8sNaming.tenantServiceAccount(tenant.getServiceAccount());
        for (PlatformTenantNamespace allocation : allocations) {
            String namespace = allocation.getNamespace();
            try {
                RoleBinding existing = adminClient.rbac()
                        .roleBindings()
                        .inNamespace(namespace)
                        .withName(rbName).get();
                if (existing != null) {
                    continue;
                }

                //查询这个权限绑定的模板
                PlatformRbacTemplate template = templateMapper.selectByPrimaryKey(allocation.getRoleTemplateId());
                if (template == null) {
                    log.warn("权限模板为空。集群：{}，租户：{}，命名空间：{}", allocation.getClusterId(), allocation.getTenantId(), allocation.getNamespace());
                    return;
                }

                //创建rolebinding
                RoleBinding roleBinding = new RoleBindingBuilder()
                        .withNewMetadata()
                            .withName(rbName)
                            .withNamespace(namespace)
                        .endMetadata()
                        .withNewRoleRef()
                            .withApiGroup("rbac.authorization.k8s.io")
                            .withKind("ClusterRole")
                            .withName(K8sNaming.templateClusterRole(template.getName()))
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
     * 创建管理员client
     * @return
     */
    KubernetesClient createAdminClient(K8sCluster k8sCluster) {
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
     * 失效某集群的 admin client，以及由它派生的全部 tenant client。
     * kubeconfig 变更后旧连接作废：admin + 该集群所有租户 client 都指向旧连接，须一并清除，
     * 下次 getAdminClient/getTenantClient 会用新 kubeconfig 重建。
     */
    public void evictAdminClient(String clusterId) {
        KubernetesClient admin = adminClientCache.remove(clusterId);
        if (admin != null) {
            try {
                admin.close();
            } catch (Exception ignored) {}
        }
        List<ClientKey> staleTenantKeys = tenantClientCache.keySet().stream()
                .filter(k -> clusterId.equals(k.clusterId()))
                .toList();
        for (ClientKey key : staleTenantKeys) {
            KubernetesClient client = tenantClientCache.remove(key);
            if (client != null) {
                try {
                    client.close();
                } catch (Exception ignored) {}
            }
        }
        log.info("已失效集群 {} 的 admin/tenant client 缓存", clusterId);
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
