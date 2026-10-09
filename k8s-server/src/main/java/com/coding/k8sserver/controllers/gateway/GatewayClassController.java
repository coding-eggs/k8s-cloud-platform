package com.coding.k8sserver.controllers.gateway;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.GatewayClassDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractClusterResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 集群域 - GatewayClass（gateway.networking.k8s.io/v1，平台侧，边界=平台已注册该集群）。
 * <p>cluster-scoped、admin client、无命名空间维度；6 标准端点免费获得（POST /list、GET /{name}、
 * GET /{name}/yaml、POST、PUT /{name}、DELETE /{name}）。
 * <p>继承 {@link AbstractClusterResourceController} 即声明 {@code PLATFORM} 边界 —— 只有持
 * {@code PLATFORM_SCOPE} 的调用方能到，与挂载路径无关。租户侧要用 GatewayClass 只能走
 * platform-api 的 {@code POST /mesh/gatewayclasses}（窄投影只读引用端点，见 MeshService）。
 */
@Tag(name = "集群域-GatewayClass", description = "Gateway API GatewayClass（集群级、平台管理）")
@RestController
@RequestMapping("/gatewayclasses")
public class GatewayClassController extends AbstractClusterResourceController<GatewayClassDTO> {

    public GatewayClassController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.GATEWAY_CLASS;
    }

}
