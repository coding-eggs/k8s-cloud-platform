package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.TcpRouteDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractNamespacedResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名空间域 - TCPRoute（gateway.networking.k8s.io CRD，租户域，边界=分配表三元组）。
 * <p>同 Gateway（租户域）。CRD 版本（{@code v1} 或 {@code v1alpha2}）由 {@code KubernetesOperationsFactory.resolveL4Version} 按集群 capability 分派，本类不感知。
 */
@Tag(name = "资源管理-TCPRoute", description = "命名空间内 TCPRoute（租户域，边界=分配表）")
@RestController
@RequestMapping("/tcproutes")
public class TcpRouteController extends AbstractNamespacedResourceController<TcpRouteDTO> {

    public TcpRouteController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.TCP_ROUTE;
    }

}
