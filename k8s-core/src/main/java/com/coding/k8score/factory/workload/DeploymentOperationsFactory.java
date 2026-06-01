package com.coding.k8score.factory.workload;

import com.coding.k8score.config.ResourceCapability;
import com.coding.k8score.converter.impl.AppsV1DeploymentConverter;
import com.coding.common.models.k8s.dto.DeploymentDTO;
import com.coding.common.models.k8s.ResourceType;
import com.coding.k8score.factory.CommonOperationsFactory;
import com.coding.k8score.operations.AppV1DeploymentOperations;
import com.coding.k8score.operations.CommonOperations;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * deployment 工厂，目前只支持V1 apiVersion的api，如需要支持其他版本的可以更改工厂代码
 */
@Component
@AllArgsConstructor
public class DeploymentOperationsFactory implements CommonOperationsFactory<DeploymentDTO> {

    @Override
    public ResourceType type() {
        return ResourceType.DEPLOYMENT;
    }


    @Override
    public CommonOperations<DeploymentDTO> get(KubernetesClient client, ResourceCapability resourceCapability, String apiVersion) {
        if ((!StringUtils.hasText(apiVersion) || AppV1DeploymentOperations.apiVersion.equals(apiVersion))
                && resourceCapability.isDeploymentV1()) {
            return new AppV1DeploymentOperations(client, new AppsV1DeploymentConverter());
        }else {
            //TODO 其他情况备用
            return null;
        }
    }
}
