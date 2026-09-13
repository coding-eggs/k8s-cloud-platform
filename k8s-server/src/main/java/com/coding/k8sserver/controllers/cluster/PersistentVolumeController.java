package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.PersistentVolumeDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractClusterResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 集群域 - PersistentVolume（PLATFORM:admin，边界=平台已注册该集群）。
 * <p>
 * 通用单点 CRUD；平台侧主要用 list/get/yaml 供 PVC 详情查看绑定的 PV。
 */
@Tag(name = "集群域-PersistentVolume", description = "集群级持久卷（PLATFORM:admin，边界=集群注册表）")
@RestController
@RequestMapping("/resources/persistentvolumes")
public class PersistentVolumeController extends AbstractClusterResourceController<PersistentVolumeDTO> {

    public PersistentVolumeController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.PERSISTENT_VOLUME;
    }

}
