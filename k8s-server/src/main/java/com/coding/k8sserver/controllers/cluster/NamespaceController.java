package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.NamespaceDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractClusterResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 集群域 - 命名空间（core/v1，平台级管理，边界=集群注册表）。
 * 六个标准端点由基类提供；仅 PLATFORM:admin 可访问（SecurityFilterChain 对 /admin/** 统一要求）。
 * 业务规则（managed-by / 分配表守卫 / 视图叠加）全在 platform-api 侧，本层零业务逻辑。
 */
@Tag(name = "资源管理-Namespace", description = "集群级命名空间（平台管理，边界=集群注册表）")
@RestController
@RequestMapping("/admin/namespaces")
public class NamespaceController extends AbstractClusterResourceController<NamespaceDTO> {

    public NamespaceController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.NAMESPACE;
    }

}
