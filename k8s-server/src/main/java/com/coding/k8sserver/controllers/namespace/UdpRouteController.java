package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.UdpRouteDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractNamespacedResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名空间域 - UDPRoute（gateway.networking.k8s.io CRD，租户域，边界=分配表三元组）。
 * <p>同 Gateway（租户域）。与 TCPRoute 同构；CRD 版本分派同 TCPRoute。
 */
@Tag(name = "资源管理-UDPRoute", description = "命名空间内 UDPRoute（租户域，边界=分配表）")
@RestController
@RequestMapping("/udproutes")
public class UdpRouteController extends AbstractNamespacedResourceController<UdpRouteDTO> {

    public UdpRouteController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.UDP_ROUTE;
    }

}
