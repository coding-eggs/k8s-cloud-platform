package com.coding.k8sserver.controllers.namespace;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.ReplicaSetDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractNamespacedResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名空间域 - ReplicaSet 查看（只读 list/get/yaml，双模访问，边界=分配表）。
 * ReplicaSet 由 Deployment 控制器管理：无 create/update/delete（基类端点覆写为拒绝）。
 * 平台侧主要用途：按 labelSelector 列出某 Deployment 名下的 RS，供工作负载指标归并 pod-owner。
 */
@Tag(name = "资源管理-ReplicaSet", description = "查看命名空间内 ReplicaSet（只读，双模访问，边界=分配表）")
@RestController
@RequestMapping("/resources/replicasets")
public class ReplicaSetController extends AbstractNamespacedResourceController<ReplicaSetDTO> {

    public ReplicaSetController(KubernetesOperationsFactory operationsFactory,
                                ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.REPLICA_SET;
    }

    /**ReplicaSet 由 Deployment 管理：不支持直接创建 */
    @Override
    public ResponseData<ReplicaSetDTO> create(String tenantId, String clusterId, ReplicaSetDTO body) {
        throw new CloudPlatformException(EnumResponseType.OPERATION_NOT_SUPPORTED);
    }

    /**ReplicaSet 由 Deployment 管理：不支持直接更新 */
    @Override
    public ResponseData<ReplicaSetDTO> update(String name, String tenantId, String clusterId, ReplicaSetDTO body) {
        throw new CloudPlatformException(EnumResponseType.OPERATION_NOT_SUPPORTED);
    }

    /**ReplicaSet 由 Deployment 管理：不支持直接删除 */
    @Override
    public ResponseData<Void> delete(String name, String tenantId, String clusterId, String namespace) {
        throw new CloudPlatformException(EnumResponseType.OPERATION_NOT_SUPPORTED);
    }

}
