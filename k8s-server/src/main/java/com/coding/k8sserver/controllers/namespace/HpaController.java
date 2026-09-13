package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.HpaDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractNamespacedResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名空间域 - HPA（双模访问，边界=分配表）。HPA 为跨版本发散资源：V1/V2 converter 由 factory 按集群 capability 分派。
 */
@Tag(name = "资源管理-HPA", description = "命名空间内 HorizontalPodAutoscaler（双模访问，边界=分配表；autoscaling v1/v2 按集群能力分派）")
@RestController
@RequestMapping("/resources/hpas")
public class HpaController extends AbstractNamespacedResourceController<HpaDTO> {

    public HpaController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.HPA;
    }

}
