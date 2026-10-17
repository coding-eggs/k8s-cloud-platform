package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.PrometheusRuleDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractNamespacedResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名空间域 - PrometheusRule（monitoring.coreos.com/v1 CRD，双模访问，边界=分配表）。
 * 集群未装 Prometheus Operator 时 apiserver 返回 404，由上层异常体系透出。
 */
@Tag(name = "资源管理-PrometheusRule", description = "命名空间内 PrometheusRule（双模访问，边界=分配表）")
@RestController
@RequestMapping("/prometheusrules")
public class PrometheusRuleController extends AbstractNamespacedResourceController<PrometheusRuleDTO> {

    public PrometheusRuleController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.PROMETHEUS_RULE;
    }

}
