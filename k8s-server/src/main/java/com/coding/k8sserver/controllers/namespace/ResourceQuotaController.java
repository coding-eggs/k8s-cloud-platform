package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.ResourceQuotaDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractNamespacedResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名空间域 - ResourceQuota（平台管理，<b>平台边界</b>：仅集群可达性校验 + admin client）。
 * <p>
 * 与 Pod/ConfigMap 等租户资源同属"命名空间级资源"（同一个基类、同一套六端点），差别只在
 * {@link #accessBoundary()}：配额是平台级开通流程的一环，作用对象常是「尚未分配给任何租户」的命名空间，
 * 经租户边界（分配表三元组）会被 ResourceAccessResolver 直接拒。
 * <p>
 * 声明 {@link AccessBoundary#PLATFORM} 后，{@code BoundaryAuthorizationManager} 会要求调用方持有
 * {@code PLATFORM_SCOPE} —— 这才是"免分配表校验的路径不可能被租户触达"的保证（与挂载路径无关）。
 * status.used 由 apiserver 的 quota controller 写，本侧只读回显。
 */
@Tag(name = "资源管理-ResourceQuota", description = "命名空间内资源配额（平台管理，平台边界）")
@RestController
@RequestMapping("/resourcequotas")
public class ResourceQuotaController extends AbstractNamespacedResourceController<ResourceQuotaDTO> {

    public ResourceQuotaController(KubernetesOperationsFactory operationsFactory,
                                   ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.RESOURCE_QUOTA;
    }

    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.PLATFORM;
    }

}
