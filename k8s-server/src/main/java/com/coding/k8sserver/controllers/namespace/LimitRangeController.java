package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.LimitRangeDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractNamespacedResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名空间域 - LimitRange（平台管理，<b>平台边界</b>：仅集群可达性校验 + admin client）。
 * <p>
 * 与 ResourceQuota 同为命名空间约束资源，理由相同：作用对象常是「尚未分配给任何租户」的命名空间，
 * 走租户边界会被分配表直接拒，故 {@link #accessBoundary()} 为 PLATFORM。LimitRange 无 status，
 * 本侧无只读回显。
 */
@Tag(name = "资源管理-LimitRange", description = "命名空间内限制范围（平台管理，平台边界）")
@RestController
@RequestMapping("/limitranges")
public class LimitRangeController extends AbstractNamespacedResourceController<LimitRangeDTO> {

    public LimitRangeController(KubernetesOperationsFactory operationsFactory,
                                ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.LIMIT_RANGE;
    }

    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.PLATFORM;
    }

}
