package com.coding.k8score.factory.rbac;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.ClusterRoleDTO;
import com.coding.k8score.config.ResourceCapability;
import com.coding.k8score.converter.impl.rbac.RbacV1ClusterRoleConverter;
import com.coding.k8score.factory.ResourceOperationsFactory;
import com.coding.k8score.operations.ClusterOperations;
import com.coding.k8score.operations.rbac.RbacV1ClusterRoleOperations;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * ClusterRole 工厂，目前只支持 rbac/v1 apiVersion
 */
@Component
@AllArgsConstructor
public class ClusterRoleOperationsFactory implements ResourceOperationsFactory<ClusterRoleDTO> {

    @Override
    public ResourceType type() {
        return ResourceType.CLUSTER_ROLE;
    }

    @Override
    public ClusterOperations<ClusterRoleDTO> get(KubernetesClient client, ResourceCapability capability, String apiVersion) {
        if ((!StringUtils.hasText(apiVersion) || RbacV1ClusterRoleOperations.apiVersion.equals(apiVersion))
                && capability.isClusterRoleV1()) {
            return new RbacV1ClusterRoleOperations(client, new RbacV1ClusterRoleConverter());
        } else {
            // TODO 其他情况备用
            return null;
        }
    }

}