package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.WorkloadDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractNamespacedResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名空间域 - 工作负载统一管理（Deployment/StatefulSet/DaemonSet，kind 在 DTO 内区分）。
 * list 返回三种 kind 合并结果；get/delete/yaml 按名跨 kind 查找（k8s-core 侧实现）。
 */
@Tag(name = "资源管理-工作负载", description = "命名空间内工作负载 Deployment/StatefulSet/DaemonSet（双模访问，边界=分配表）")
@RestController
@RequestMapping("/resources/workloads")
public class WorkloadController extends AbstractNamespacedResourceController<WorkloadDTO> {

    public WorkloadController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.WORKLOAD;
    }

}
