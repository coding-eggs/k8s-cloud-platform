package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.StorageClassDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractClusterResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 集群域 - StorageClass（PLATFORM:admin，边界=平台已注册该集群）。
 * <p>
 * 通用单点 CRUD；平台侧主要用 list/get/yaml 供工作负载编辑器下拉选择 storageClassName。
 */
@Tag(name = "集群域-StorageClass", description = "集群级存储类（PLATFORM:admin，边界=集群注册表）")
@RestController
@RequestMapping("/resources/storageclasses")
public class StorageClassController extends AbstractClusterResourceController<StorageClassDTO> {

    public StorageClassController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.STORAGE_CLASS;
    }

}
