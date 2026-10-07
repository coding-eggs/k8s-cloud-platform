package com.coding.k8sserver.controllers.calico;

import com.coding.common.models.k8s.dto.CalicoFormOptionDTO;
import com.coding.common.models.k8s.dto.SecretRefOptionDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.calico.CalicoFormOptionOperations;
import com.coding.k8sserver.components.ResourceAccessResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 集群域 - BGP 编辑器下拉候选（PLATFORM:admin，边界=平台已注册该集群）。只读、admin client。
 * <p>非六端点形态的自定义端点，不套 {@code AbstractClusterResourceController}；
 * K8s 语义全在 {@link CalicoFormOptionOperations}，本类只做边界校验 + 委托。
 */
@Tag(name = "集群域-Calico 表单候选", description = "BGP 编辑器下拉：命名空间 / Secret 引用 / 工作负载（PLATFORM:admin）")
@RestController
@RequestMapping("/admin/calico/form-options")
public class CalicoFormOptionController {

    private final KubernetesOperationsFactory operationsFactory;
    private final ResourceAccessResolver accessResolver;

    public CalicoFormOptionController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        this.operationsFactory = operationsFactory;
        this.accessResolver = accessResolver;
    }

    @PostMapping
    @Operation(summary = "BGP 编辑器下拉候选（namespaces / workloads）")
    public ResponseData<CalicoFormOptionDTO> formOptions(@RequestParam String clusterId) {
        accessResolver.assertClusterAccess(clusterId);
        return new ResponseData<>(ops(clusterId).listOptions());
    }

    @GetMapping("/secrets")
    @Operation(summary = "某命名空间下的 Secret 引用候选（name + data keys；级联第二级）")
    public ResponseData<List<SecretRefOptionDTO>> secretOptions(@RequestParam String clusterId,
                                                                @RequestParam String namespace) {
        accessResolver.assertClusterAccess(clusterId);
        return new ResponseData<>(ops(clusterId).listSecrets(namespace));
    }

    private CalicoFormOptionOperations ops(String clusterId) {
        return operationsFactory.getCalicoFormOptionOperation(clusterId);
    }

}
