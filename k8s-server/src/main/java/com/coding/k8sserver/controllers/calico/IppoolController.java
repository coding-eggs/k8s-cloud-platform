package com.coding.k8sserver.controllers.calico;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.IpoolDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractClusterResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 集群域 - Calico IPPool（PLATFORM:admin，边界=平台已注册该集群）。
 * <p>cluster-scoped、admin client、无命名空间维度；6 标准端点免费获得（POST /list、GET /{name}、
 * GET /{name}/yaml、POST、PUT /{name}、DELETE /{name}）。删除守卫在 platform-api 侧（见 CalicoService）。
 */
@Tag(name = "集群域-Calico IPPool", description = "Calico IPPool（PLATFORM:admin，边界=集群注册表）")
@RestController
@RequestMapping("/admin/calico/ippool")
public class IppoolController extends AbstractClusterResourceController<IpoolDTO> {

    public IppoolController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.IP_POOL;
    }

}
