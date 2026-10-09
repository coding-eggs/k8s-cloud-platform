package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.HttpRouteDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractNamespacedResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名空间域 - HTTPRoute（gateway.networking.k8s.io/v1 CRD，租户域，边界=分配表三元组）。
 * <p>同 Gateway（租户域）。update 的 fetch-overlay 在 {@code HttpRouteOperations} / {@code HttpRouteConverter}
 * —— rules/matches/filters/backendRefs 都是 atomic list，不 overlay 会丢未建模子字段。
 */
@Tag(name = "资源管理-HTTPRoute", description = "命名空间内 HTTPRoute（租户域，边界=分配表）")
@RestController
@RequestMapping("/httproutes")
public class HttpRouteController extends AbstractNamespacedResourceController<HttpRouteDTO> {

    public HttpRouteController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.HTTP_ROUTE;
    }

}
