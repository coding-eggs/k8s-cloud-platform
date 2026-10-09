package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.TlsRouteDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractNamespacedResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名空间域 - TLSRoute（gateway.networking.k8s.io CRD，租户域，边界=分配表三元组）。
 * <p>同 Gateway（租户域）。比 TCP/UDPRoute 多 {@code spec.hostnames}（SNI 匹配，在 converter 里建模）；CRD 版本分派同 TCPRoute。
 */
@Tag(name = "资源管理-TLSRoute", description = "命名空间内 TLSRoute（租户域，边界=分配表）")
@RestController
@RequestMapping("/tlsroutes")
public class TlsRouteController extends AbstractNamespacedResourceController<TlsRouteDTO> {

    public TlsRouteController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.TLS_ROUTE;
    }

}
