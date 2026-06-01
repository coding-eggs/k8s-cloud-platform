package com.coding.k8score.factory;

import com.coding.data.mapper.k8s.K8sClusterMapper;
import com.coding.data.models.k8s.K8sCluster;
import com.coding.k8score.config.ResourceCapability;
import io.fabric8.kubernetes.api.model.APIResource;
import io.fabric8.kubernetes.api.model.APIResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@ConditionalOnBean(KubernetesClientFactory.class)
public class ResourceCapabilityFactory {

    private final ConcurrentHashMap<String, ResourceCapability> cache = new ConcurrentHashMap<>();

    @Autowired
    private K8sClusterMapper clusterMapper;

    @Autowired
    private KubernetesClientFactory clientFactory;

    /**
     * 初始化集群能力
     */
    @PostConstruct
    public void init () {
        putAllClusterCapability();
        log.info("初始化集群能力信息结束");
    }

    /**
     * 定时更新集群能力
     */
    @Scheduled(fixedDelay = 10 * 60 * 1000) // 10分钟
    public void refreshAllClusterCapability() {
        putAllClusterCapability();
    }
    
    public void putAllClusterCapability() {
        List<K8sCluster> k8sClusterList = clusterMapper.listAll();
        for (K8sCluster k8sCluster : k8sClusterList) {
            if (k8sCluster.getEnabled() == 1) {
                KubernetesClient client = clientFactory.getAdminClient(k8sCluster.getClusterId());
                cache.put(k8sCluster.getClusterId(), buildCapability(client));
            }
        }
    }

    public ResourceCapability get(String clusterId) {
        if (!cache.containsKey(clusterId)) {
            put(clusterId);
        }
        return cache.get(clusterId);

    }

    public void put(String clusterId) {
        KubernetesClient client = clientFactory.getAdminClient(clusterId);
        cache.put(clusterId, buildCapability(client));
    }

    /**
     * 获取集群能力
     * @param client
     * @return
     */
    public ResourceCapability buildCapability(KubernetesClient client) {
        ResourceCapability cap = new ResourceCapability();
        // ===== Workload =====
        cap.setDeploymentV1(support(client,"apps", "v1", "deployments"));
        cap.setStatefulSetV1(support(client,"apps", "v1", "statefulsets"));
        cap.setDaemonSetV1(support(client,"apps", "v1", "daemonsets"));

        // ===== Network =====
        cap.setIngressV1(support(client,"networking.k8s.io", "v1", "ingresses"));
        cap.setIngressV1Beta1(support(client,"extensions", "v1beta1", "ingresses"));

        // ===== Batch =====
        cap.setCronJobV1(support(client,"batch", "v1", "cronjobs"));
        cap.setCronJobV1Beta1(support(client,"batch", "v1beta1", "cronjobs"));

        // ===== 扩展能力 =====
        cap.setSupportHPA(support(client,"autoscaling", "v2", "horizontalpodautoscalers"));

        return cap;
    }

    public boolean support(KubernetesClient client, String group, String version, String resource) {
        try {
            APIResourceList list = client.getApiResources(group + "/" + version);
            if (list == null || list.getResources() == null) {
                return false;
            }
            for (APIResource r : list.getResources()) {
                if (resource.equals(r.getName())) {
                    return true;
                }
            }
        } catch (Exception e) {
            //TODO API 不存在会抛异常
            return false;
        }
        return false;
    }

}
