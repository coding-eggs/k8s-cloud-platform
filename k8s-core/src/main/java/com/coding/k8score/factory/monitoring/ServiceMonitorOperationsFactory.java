package com.coding.k8score.factory.monitoring;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.ServiceMonitorDTO;
import com.coding.k8score.config.ResourceCapability;
import com.coding.k8score.converter.impl.monitoring.ServiceMonitorConverter;
import com.coding.k8score.factory.ResourceOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.monitoring.ServiceMonitorOperations;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * servicemonitor 工厂：CRD 资源，集群未装 Prometheus Operator 时调用报 404（由上层透出）
 */
@Component
@AllArgsConstructor
public class ServiceMonitorOperationsFactory implements ResourceOperationsFactory<ServiceMonitorDTO> {

    @Override
    public ResourceType type() {
        return ResourceType.SERVICE_MONITOR;
    }

    @Override
    public NamespacedOperations<ServiceMonitorDTO> get(KubernetesClient client, ResourceCapability resourceCapability, String apiVersion) {
        return new ServiceMonitorOperations(client, new ServiceMonitorConverter());
    }

}
