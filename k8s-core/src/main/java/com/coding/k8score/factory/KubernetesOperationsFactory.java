package com.coding.k8score.factory;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.k8score.config.ResourceCapability;
import com.coding.common.models.k8s.ResourceType;
import com.coding.k8score.operations.ClusterOperations;
import com.coding.k8score.operations.NamespacedOperations;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * operations 总工厂，可以通过此工厂直接拿到任意 operation
 */
@Component
public class KubernetesOperationsFactory {

    private final KubernetesClientFactory clientFactory;
    private final ResourceCapabilityFactory capabilityFactory;
    private final Map<ResourceType, ResourceOperationsFactory<?>> factoryMap;

    public KubernetesOperationsFactory(KubernetesClientFactory clientFactory,
                                       ResourceCapabilityFactory capabilityFactory,
                                       List<ResourceOperationsFactory<?>> factories) {
        this.clientFactory = clientFactory;
        this.capabilityFactory = capabilityFactory;
        this.factoryMap = factories.stream()
                .collect(Collectors.toMap(
                        ResourceOperationsFactory::type,
                        f -> f,
                        (a, b) -> {
                            throw new IllegalStateException("Duplicate factory for type: " + a.type());
                        }
                ));
    }

    /**
     * 获取命名空间级资源 operation（Deployment、RoleBinding、ServiceAccount 等）
     * 使用 tenant client，实现租户隔离
     */
    @SuppressWarnings("unchecked")
    public <T> NamespacedOperations<T> getNamespacedOperation(ResourceType resourceType, String clusterId, String tenantId, String apiVersion) {
        ResourceOperationsFactory<?> factory = factoryMap.get(resourceType);
        if (factory == null) {
            throw new CloudPlatformException(EnumResponseType.NON_RESOURCE);
        }
        KubernetesClient client = clientFactory.getTenantClient(clusterId, tenantId);
        ResourceCapability capability = capabilityFactory.get(clusterId);
        return (NamespacedOperations<T>) factory.get(client, capability, apiVersion);
    }

    /**
     * 获取命名空间级资源 operation（admin 权限）
     * 平台侧开通/清理流程使用：创建租户 SA、RoleBinding 等不能走 tenant client（身份尚未就绪）
     */
    @SuppressWarnings("unchecked")
    public <T> NamespacedOperations<T> getAdminNamespacedOperation(ResourceType resourceType, String clusterId, String apiVersion) {
        ResourceOperationsFactory<?> factory = factoryMap.get(resourceType);
        if (factory == null) {
            throw new CloudPlatformException(EnumResponseType.NON_RESOURCE);
        }
        KubernetesClient client = clientFactory.getAdminClient(clusterId);
        ResourceCapability capability = capabilityFactory.get(clusterId);
        return (NamespacedOperations<T>) factory.get(client, capability, apiVersion);
    }

    /**
     * 获取集群级资源 operation（ClusterRole 等）
     * 使用 admin client，操作集群级别资源
     */
    @SuppressWarnings("unchecked")
    public <T> ClusterOperations<T> getClusterOperation(ResourceType resourceType, String clusterId, String apiVersion) {
        ResourceOperationsFactory<?> factory = factoryMap.get(resourceType);
        if (factory == null) {
            throw new CloudPlatformException(EnumResponseType.NON_RESOURCE);
        }
        KubernetesClient client = clientFactory.getAdminClient(clusterId);
        ResourceCapability capability = capabilityFactory.get(clusterId);
        return (ClusterOperations<T>) factory.get(client, capability, apiVersion);
    }

}