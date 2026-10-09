package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.GatewayDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractNamespacedResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名空间域 - Gateway（gateway.networking.k8s.io/v1 CRD，租户域，边界=分配表三元组）。
 * <p>归属决策（2026-10-08）：Gateway 与 Route 同为<b>租户域</b> —— 与 ServiceMonitor 同形，
 * 复用现有 {@code AbstractNamespacedResourceController} + {@code K8sResourceClient} 通路，
 * 无需新基类；命名空间边界由分配表天然生效。
 * <p>集群未装 Gateway API 时 apiserver 返回 404，由上层异常体系透出（前端横幅 + create 禁用）。
 */
@Tag(name = "资源管理-Gateway", description = "命名空间内 Gateway（租户域，边界=分配表）")
@RestController
@RequestMapping("/gateways")
public class GatewayController extends AbstractNamespacedResourceController<GatewayDTO> {

    public GatewayController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.GATEWAY;
    }

}
