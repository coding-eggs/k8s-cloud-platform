package com.coding.k8score.factory.core;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.SecretDTO;
import com.coding.k8score.config.ResourceCapability;
import com.coding.k8score.converter.impl.core.CoreV1SecretConverter;
import com.coding.k8score.factory.ResourceOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.core.CoreV1SecretOperations;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * secret 工厂：corev1 资源任何集群都有，无需 capability 判断
 */
@Component
@AllArgsConstructor
public class SecretOperationsFactory implements ResourceOperationsFactory<SecretDTO> {

    @Override
    public ResourceType type() {
        return ResourceType.SECRET;
    }

    @Override
    public NamespacedOperations<SecretDTO> get(KubernetesClient client, ResourceCapability resourceCapability, String apiVersion) {
        return new CoreV1SecretOperations(client, new CoreV1SecretConverter());
    }

}
