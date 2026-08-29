package com.coding.k8score.factory.workload;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.WorkloadDTO;
import com.coding.k8score.config.ResourceCapability;
import com.coding.k8score.converter.impl.workload.WorkloadConverter;
import com.coding.k8score.factory.ResourceOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.workload.WorkloadOperations;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 工作负载统一工厂（Deployment/StatefulSet/DaemonSet 合一，kind 在 DTO 内区分）。
 * 与 {@link DeploymentOperationsFactory}（租户域单类型）并存：WORKLOAD 供管理端资源页使用。
 */
@Component
@AllArgsConstructor
public class WorkloadOperationsFactory implements ResourceOperationsFactory<WorkloadDTO> {

    @Override
    public ResourceType type() {
        return ResourceType.WORKLOAD;
    }

    @Override
    public NamespacedOperations<WorkloadDTO> get(KubernetesClient client, ResourceCapability resourceCapability, String apiVersion) {
        return new WorkloadOperations(client, new WorkloadConverter());
    }

}
