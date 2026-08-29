package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.ConfigMapDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractNamespacedResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名空间域 - ConfigMap（全链路参考实现，其余资源按同模式扩展）。
 * <p>
 * 双模访问：租户 token（tenantId 取 claim，可不传）/ admin token（显式 tenantId 代操作）；
 * 端点形态与边界流程统一由 {@link AbstractNamespacedResourceController} 提供。
 */
@Tag(name = "资源管理-ConfigMap", description = "命名空间内 ConfigMap（双模访问，边界=分配表）")
@RestController
@RequestMapping("/resources/configmaps")
public class ConfigMapController extends AbstractNamespacedResourceController<ConfigMapDTO> {

    public ConfigMapController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.CONFIGMAP;
    }

}
