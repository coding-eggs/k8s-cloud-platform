package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.GrpcRouteDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractNamespacedResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名空间域 - GRPCRoute（gateway.networking.k8s.io CRD，租户域，边界=分配表三元组）。
 * <p>同 Gateway（租户域）。update 的分层 fetch-overlay 在 {@code GrpcRouteOperations} / {@code GrpcRouteConverter} —— rules/matches/filters/backendRefs 都是 atomic list，不 overlay 会丢未建模子字段。
 */
@Tag(name = "资源管理-GRPCRoute", description = "命名空间内 GRPCRoute（租户域，边界=分配表）")
@RestController
@RequestMapping("/grpcroutes")
public class GrpcRouteController extends AbstractNamespacedResourceController<GrpcRouteDTO> {

    public GrpcRouteController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.GRPC_ROUTE;
    }

}
