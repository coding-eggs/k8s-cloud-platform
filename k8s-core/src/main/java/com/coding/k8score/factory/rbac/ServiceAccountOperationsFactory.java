package com.coding.k8score.factory.rbac;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.ServiceAccountDTO;
import com.coding.k8score.config.ResourceCapability;
import com.coding.k8score.converter.impl.rbac.CoreV1ServiceAccountConverter;
import com.coding.k8score.factory.ResourceOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.rbac.CoreV1ServiceAccountOperations;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * ServiceAccount 工厂，目前只支持 v1 apiVersion
 */
@Component
@AllArgsConstructor
public class ServiceAccountOperationsFactory implements ResourceOperationsFactory<ServiceAccountDTO> {

    @Override
    public ResourceType type() {
        return ResourceType.SERVICE_ACCOUNT;
    }

    @Override
    public NamespacedOperations<ServiceAccountDTO> get(KubernetesClient client, ResourceCapability capability, String apiVersion) {
        if ((!StringUtils.hasText(apiVersion) || CoreV1ServiceAccountOperations.apiVersion.equals(apiVersion)) && capability.isServiceAccountV1()) {
            return new CoreV1ServiceAccountOperations(client, new CoreV1ServiceAccountConverter());
        } else {
            // TODO 其他情况备用
            return null;
        }
    }

}