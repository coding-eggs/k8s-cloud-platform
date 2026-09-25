package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.ResourceQuotaDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractAdminNamespacedResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名空间域 - ResourceQuota（平台管理，边界=集群注册表）。
 * <p>
 * 走 admin 边界而非租户边界：配额是平台级开通流程的一环，作用对象常是「尚未分配给任何租户」的命名空间，
 * 经租户边界会被 ResourceAccessResolver 直接拒。故 namespace 为透传参数，本类只做集群可达性校验
 * （见 {@link AbstractAdminNamespacedResourceController}），无业务逻辑。
 * status.used 由 apiserver 的 quota controller 写，本侧只读回显。
 */
@Tag(name = "资源管理-ResourceQuota", description = "命名空间内资源配额（平台管理，边界=集群注册表）")
@RestController
@RequestMapping("/admin/resourcequotas")
public class ResourceQuotaController extends AbstractAdminNamespacedResourceController<ResourceQuotaDTO> {

    public ResourceQuotaController(KubernetesOperationsFactory operationsFactory,
                                   ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.RESOURCE_QUOTA;
    }

}
