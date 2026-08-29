package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.SecretDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractNamespacedResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名空间域 - Secret（双模访问，边界=分配表）。约定同 ConfigMapController。
 */
@Tag(name = "资源管理-Secret", description = "命名空间内 Secret（双模访问，边界=分配表）")
@RestController
@RequestMapping("/resources/secrets")
public class SecretController extends AbstractNamespacedResourceController<SecretDTO> {

    public SecretController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.SECRET;
    }

}
