package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.PersistentVolumeClaimDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractNamespacedResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名空间域 - PVC（双模访问，边界=分配表）。
 * 注意：PVC spec 创建后不可变，更新仅同步标签（k8s-core 侧实现）。
 */
@Tag(name = "资源管理-PVC", description = "命名空间内 PVC（双模访问，边界=分配表）")
@RestController
@RequestMapping("/resources/persistentvolumeclaims")
public class PvcController extends AbstractNamespacedResourceController<PersistentVolumeClaimDTO> {

    public PvcController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.PERSISTENT_VOLUME_CLAIM;
    }

}
