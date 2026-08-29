package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.ServiceMonitorDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractNamespacedResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名空间域 - ServiceMonitor（monitoring.coreos.com/v1 CRD，双模访问，边界=分配表）。
 * 集群未装 Prometheus Operator 时 apiserver 返回 404，由上层异常体系透出。
 */
@Tag(name = "资源管理-ServiceMonitor", description = "命名空间内 ServiceMonitor（双模访问，边界=分配表）")
@RestController
@RequestMapping("/resources/servicemonitors")
public class ServiceMonitorController extends AbstractNamespacedResourceController<ServiceMonitorDTO> {

    public ServiceMonitorController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.SERVICE_MONITOR;
    }

}
