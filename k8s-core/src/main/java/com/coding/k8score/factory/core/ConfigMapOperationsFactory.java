package com.coding.k8score.factory.core;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.ConfigMapDTO;
import com.coding.k8score.config.ResourceCapability;
import com.coding.k8score.converter.impl.core.CoreV1ConfigMapConverter;
import com.coding.k8score.factory.ResourceOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.core.CoreV1ConfigMapOperations;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * configmap 工厂：corev1 资源任何集群都有，无需 capability 判断
 */
@Component
@AllArgsConstructor
public class ConfigMapOperationsFactory implements ResourceOperationsFactory<ConfigMapDTO> {

    @Override
    public ResourceType type() {
        return ResourceType.CONFIGMAP;
    }

    @Override
    public NamespacedOperations<ConfigMapDTO> get(KubernetesClient client, ResourceCapability resourceCapability, String apiVersion) {
        return new CoreV1ConfigMapOperations(client, new CoreV1ConfigMapConverter());
    }

}
