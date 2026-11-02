package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.VolumeSnapshotClassDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractClusterResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 集群域 - VolumeSnapshotClass（PLATFORM 边界，平台只读）。
 * <p>写端点继承自基类，但 ops 一律拒绝；platform-api 也只暴露 list/get。
 * 租户要的字段走 {@link VolumeSnapshotClassOptionsController}。
 */
@Tag(name = "集群域-VolumeSnapshotClass", description = "集群级快照类（平台只读）")
@RestController
@RequestMapping("/volumesnapshotclasses")
public class VolumeSnapshotClassController extends AbstractClusterResourceController<VolumeSnapshotClassDTO> {

    public VolumeSnapshotClassController(KubernetesOperationsFactory operationsFactory,
                                         ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.VOLUME_SNAPSHOT_CLASS;
    }

}
