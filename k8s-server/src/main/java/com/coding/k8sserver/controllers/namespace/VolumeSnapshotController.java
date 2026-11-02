package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.VolumeSnapshotDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractNamespacedResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名空间域 - VolumeSnapshot（snapshot.storage.k8s.io/v1 CRD，双模访问，边界=分配表）。
 * <p>集群没装快照 CRD 时 apiserver 返回 404，由上层异常体系透出；更新不支持（快照 spec 不可变）。
 */
@Tag(name = "资源管理-VolumeSnapshot", description = "命名空间内卷快照（双模访问，边界=分配表）")
@RestController
@RequestMapping("/volumesnapshots")
public class VolumeSnapshotController extends AbstractNamespacedResourceController<VolumeSnapshotDTO> {

    public VolumeSnapshotController(KubernetesOperationsFactory operationsFactory,
                                    ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.VOLUME_SNAPSHOT;
    }

}
