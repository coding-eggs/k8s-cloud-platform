package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.LimitRangeDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractAdminNamespacedResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名空间域 - LimitRange（平台管理，边界=集群注册表）。
 * <p>
 * 与 ResourceQuota 同为命名空间约束资源，走 admin 边界而非租户边界：作用对象常是「尚未分配给任何租户」的
 * 命名空间，经租户边界会被 ResourceAccessResolver 直接拒。故 namespace 为透传参数，本类只做集群可达性校验
 * （见 {@link AbstractAdminNamespacedResourceController}），零业务逻辑。LimitRange 无 status，本侧无只读回显。
 */
@Tag(name = "资源管理-LimitRange", description = "命名空间内限制范围（平台管理，边界=集群注册表）")
@RestController
@RequestMapping("/admin/limitranges")
public class LimitRangeController extends AbstractAdminNamespacedResourceController<LimitRangeDTO> {

    public LimitRangeController(KubernetesOperationsFactory operationsFactory,
                                ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.LIMIT_RANGE;
    }

}
