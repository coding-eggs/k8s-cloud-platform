package com.coding.k8sserver.controllers.calico;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.BgpPeerDTO;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.controllers.base.AbstractClusterResourceController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 集群域 - Calico BGPPeer（平台侧，边界=平台已注册该集群）。CRUD 6 标准端点。
 * <p>K8s/Calico 语义全在 {@code BgpPeerOperations}，本类只做边界校验 + 委托。
 */
@Tag(name = "集群域-Calico BGPPeer", description = "Calico BGPPeer CRUD（平台侧，边界=集群注册表）")
@RestController
@RequestMapping("/calico/bgppeer")
public class BgpPeerController extends AbstractClusterResourceController<BgpPeerDTO> {

    public BgpPeerController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        super(operationsFactory, accessResolver);
    }

    @Override
    protected ResourceType resourceType() {
        return ResourceType.BGP_PEER;
    }
}
