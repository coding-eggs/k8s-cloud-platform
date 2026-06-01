package com.coding.k8score.factory;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.constants.SystemConstant;
import com.coding.common.utils.AESUtils;
import com.coding.data.mapper.auth.PlatformTenantMapper;
import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.k8s.K8sCluster;
import io.fabric8.kubernetes.api.model.authentication.TokenRequest;
import io.fabric8.kubernetes.client.Config;
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

    @Value("${k8s.cloud.kubeconfig.aes-key:daXs1znnIStfQCVFyC8cvuS9OQZRTgeBJLLrrvu/hUM=}")
    private String AES_KEY;


    private final Map<String, KubernetesClient> adminClientCache = new ConcurrentHashMap<>();

    private final Map<ClientKey, KubernetesClient> tenantClientCache = new ConcurrentHashMap<>();


    @PostConstruct
    public void init () {
        List<K8sCluster> k8sClusterList = k8sClusterMapper.listAll();
        for (K8sCluster k8sCluster : k8sClusterList) {
            if (k8sCluster.getEnabled() == 1) {
                adminClientCache.put(k8sCluster.getClusterId(), createAdminClient(k8sCluster));
            }
        }
        log.info("初始化所有集群admin客户端缓存结束");

        List<PlatformTenant> platformTenantList = tenantMapper.listAll();
        for (PlatformTenant platformTenant : platformTenantList) {
            if (platformTenant.getStatus() == 1) {
                KubernetesClient adminClient = getAdminClient(platformTenant.getClusterId());
                createTenantClient(adminClient, new ClientKey(platformTenant.getClusterId(), platformTenant.getId()));
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
        KubernetesClient adminClient = adminClientCache.get(clusterId);
        if (!tenantClientCache.containsKey(key)) {
            tenantClientCache.put(key, createTenantClient(adminClient, key));
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

        PlatformTenant tenant = tenantMapper.selectByTenantId(clusterId, tenantId);
        if (tenant == null) {
            throw new CloudPlatformException(EnumResponseType.TENANT_NOT_EXIST);
        }

        TokenRequest tokenRequest = adminClient.serviceAccounts()
                .inNamespace(SystemConstant.SYSTEM_NAMESPACE)
                .withName(tenant.getServiceAccount())
                .tokenRequest();

        if (tokenRequest == null) {
            throw new CloudPlatformException(EnumResponseType.SERVICE_ACCOUNT_NOT_EXIST);
        }
        String token = tokenRequest.getStatus().getToken();
        Config config = Config.builder().withMasterUrl(adminClient.getMasterUrl().toString()).withOauthToken(token).build();
        return new KubernetesClientBuilder()
                .withConfig(config)
                .build();

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
