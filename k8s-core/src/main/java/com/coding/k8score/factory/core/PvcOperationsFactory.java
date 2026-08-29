package com.coding.k8score.factory.core;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO;
import com.coding.k8score.config.ResourceCapability;
import com.coding.k8score.converter.impl.core.CoreV1PvcConverter;
import com.coding.k8score.factory.ResourceOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.core.CoreV1PersistentVolumeClaimOperations;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * pvc 工厂：corev1 资源任何集群都有，无需 capability 判断
 */
@Component
@AllArgsConstructor
public class PvcOperationsFactory implements ResourceOperationsFactory<PersistentVolumeClaimDTO> {

    @Override
    public ResourceType type() {
        return ResourceType.PERSISTENT_VOLUME_CLAIM;
    }

    @Override
    public NamespacedOperations<PersistentVolumeClaimDTO> get(KubernetesClient client, ResourceCapability resourceCapability, String apiVersion) {
        return new CoreV1PersistentVolumeClaimOperations(client, new CoreV1PvcConverter());
    }

}
