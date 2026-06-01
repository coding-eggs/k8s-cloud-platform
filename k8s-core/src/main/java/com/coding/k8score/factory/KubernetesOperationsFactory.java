package com.coding.k8score.factory;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.k8score.config.ResourceCapability;
import com.coding.common.models.k8s.ResourceType;
import com.coding.k8score.operations.CommonOperations;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * operations 总工厂，可以通过此工厂直接拿到任意operation
 */
@Component
public class KubernetesOperationsFactory {

    private final KubernetesClientFactory clientFactory;
    private final ResourceCapabilityFactory capabilityFactory;
    private final Map<ResourceType, CommonOperationsFactory<?>> factoryMap;

    public KubernetesOperationsFactory(KubernetesClientFactory clientFactory,
                                       ResourceCapabilityFactory capabilityFactory,
                                       List<CommonOperationsFactory<?>> factories) {
        this.clientFactory = clientFactory;
        this.capabilityFactory = capabilityFactory;
        this.factoryMap = factories.stream()
                .collect(Collectors.toMap(
                        CommonOperationsFactory::type,
                        f -> f,
                        (a, b) -> {
                            throw new IllegalStateException("Duplicate factory for type: " + a.type());
                        }
                ));
    }

    /**
     * 获取对应的 operation
     * @param resourceType 资源类型
     * @param clusterId 集群id
     * @param tenantId 租户id
     * @param apiVersion apiVersion 为空则返回默认的
     * @return operation
     * @param <T> 对应DTO
     */
    @SuppressWarnings("unchecked")
    public <T> CommonOperations<T> getOperation(ResourceType resourceType, String clusterId, String tenantId, String apiVersion) {
        CommonOperationsFactory<?> factory = factoryMap.get(resourceType);
        if (factory == null) {
            throw new CloudPlatformException(EnumResponseType.NON_RESOURCE);
        }
        KubernetesClient client = clientFactory.getTenantClient(clusterId, tenantId);
        ResourceCapability capability = capabilityFactory.get(clusterId);

        return (CommonOperations<T>) factory.get(client, capability, apiVersion);
    }

}
