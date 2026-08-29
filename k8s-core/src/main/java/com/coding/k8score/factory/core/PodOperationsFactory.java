package com.coding.k8score.factory.core;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.PodDTO;
import com.coding.k8score.config.ResourceCapability;
import com.coding.k8score.converter.impl.core.CoreV1PodConverter;
import com.coding.k8score.factory.ResourceOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.core.CoreV1PodOperations;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * pod 工厂：corev1 资源任何集群都有，无需 capability 判断
 */
@Component
@AllArgsConstructor
public class PodOperationsFactory implements ResourceOperationsFactory<PodDTO> {

    @Override
    public ResourceType type() {
        return ResourceType.POD;
    }

    @Override
    public NamespacedOperations<PodDTO> get(KubernetesClient client, ResourceCapability resourceCapability, String apiVersion) {
        return new CoreV1PodOperations(client, new CoreV1PodConverter());
    }

}
