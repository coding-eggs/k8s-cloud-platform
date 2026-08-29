package com.coding.k8score.factory.rbac;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.RoleBindingDTO;
import com.coding.k8score.config.ResourceCapability;
import com.coding.k8score.converter.impl.rbac.RbacV1RoleBindingConverter;
import com.coding.k8score.factory.ResourceOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.rbac.RbacV1RoleBindingOperations;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * RoleBinding 工厂，目前只支持 rbac/v1 apiVersion
 */
@Component
@AllArgsConstructor
public class RoleBindingOperationsFactory implements ResourceOperationsFactory<RoleBindingDTO> {

    @Override
    public ResourceType type() {
        return ResourceType.ROLE_BINDING;
    }

    @Override
    public NamespacedOperations<RoleBindingDTO> get(KubernetesClient client, ResourceCapability capability, String apiVersion) {
        if ((!StringUtils.hasText(apiVersion) || RbacV1RoleBindingOperations.apiVersion.equals(apiVersion))
                && capability.isRoleBindingV1()) {
            return new RbacV1RoleBindingOperations(client, new RbacV1RoleBindingConverter());
        } else {
            // TODO 其他情况备用
            return null;
        }
    }

}