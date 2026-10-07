package com.coding.k8sserver.controllers.calico;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.BgpConfigurationDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractClusterResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 集群域 - Calico BGPConfiguration（PLATFORM:admin，边界=平台已注册该集群）。CRUD 6 标准端点。
 * <p>⚠️ 写操作仅平台管理员可发起——权限收敛在 platform-api 端点层（code platform:bgp:config:manage，
 * 见 V2026_10_06_1 迁移）；本边界只管集群注册表校验 + admin client。K8s/Calico 语义全在 operations。
 */
@Tag(name = "集群域-Calico BGPConfiguration", description = "Calico BGPConfiguration CRUD（PLATFORM:admin，边界=集群注册表）")
@RestController
@RequestMapping("/admin/calico/bgpconfiguration")
public class BgpConfigurationController extends AbstractClusterResourceController<BgpConfigurationDTO> {

    public BgpConfigurationController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.BGP_CONFIGURATION;
    }
}
