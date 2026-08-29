package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.ClusterRoleDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractClusterResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 集群域 - ClusterRole（PLATFORM:admin，边界=平台已注册该集群）。
 * <p>
 * 通用单点 CRUD；租户侧的模板 ClusterRole（tn-tpl-*）同样走本端点，
 * 命名 / upsert 等业务规则由 platform-api 侧编排。
 */
@Tag(name = "集群域-ClusterRole", description = "集群级 RBAC 角色（PLATFORM:admin，边界=集群注册表）")
@RestController
@RequestMapping("/admin/clusterroles")
public class ClusterRoleController extends AbstractClusterResourceController<ClusterRoleDTO> {

    public ClusterRoleController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.CLUSTER_ROLE;
    }

}
