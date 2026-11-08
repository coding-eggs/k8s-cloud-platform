package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.ListenerSetDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractNamespacedResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名空间域 - ListenerSet（gateway.networking.k8s.io/v1 CRD，租户域，边界=分配表三元组）。
 * <p><b>只读</b>：写方法由基类暴露，但 {@code ListenerSetOperations} 一律拒绝（OPERATION_NOT_SUPPORTED），
 * platform-api 也只开放 list / list-all / get / yaml。平台维护的 {@code platform-ingress} 由应用发布写入
 * （{@code /application/ingress-listenerset}，不经此处）。
 */
@Tag(name = "资源管理-ListenerSet", description = "命名空间内 ListenerSet（租户域，只读）")
@RestController
@RequestMapping("/listener-sets")
public class ListenerSetController extends AbstractNamespacedResourceController<ListenerSetDTO> {

    public ListenerSetController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.LISTENER_SET;
    }
}
