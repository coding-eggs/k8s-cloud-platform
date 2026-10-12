package com.coding.k8sserver.controllers.namespace;

import com.coding.common.models.k8s.dto.NamespaceSnapshotDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import com.coding.k8sserver.components.ResourceAccessResolver;
import com.coding.k8sserver.components.ResourceAccessResolver.AccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命名空间域 - 单个命名空间的行 + 该 ns 的异常/不健康清单（`GET /namespacestats/{namespace}`）。
 * <p>边界 {@link AccessBoundary#TENANT}：身份解析走 {@code resolveNamespacedAccess} ——
 * 租户 token 以 claim 为唯一身份源、admin 代操作须显式 tenantId 且必须是平台侧身份，
 * 两者都要过 (tenantId, clusterId, namespace) 分配表三元组。故本端点**一次只回答一个命名空间**，
 * 范围由上游 platform-api 决定（不提供批量入参：批量要么逐项再校验一遍、要么留越权洞）。
 */
@Tag(name = "资源管理-Namespace", description = "单命名空间快照（租户可达，边界=分配表）")
@RestController
@RequestMapping("/namespacestats")
public class NamespaceStatController implements AccessBoundaryAware {

    private final KubernetesOperationsFactory operationsFactory;
    private final ResourceAccessResolver accessResolver;

    public NamespaceStatController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        this.operationsFactory = operationsFactory;
        this.accessResolver = accessResolver;
    }

    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.TENANT;
    }

    @GetMapping("/{namespace}")
    @Operation(summary = "单命名空间快照（行 + 异常 Pod + 不健康工作负载）")
    public ResponseData<NamespaceSnapshotDTO> stat(@PathVariable String namespace,
                                                  @RequestParam(required = false) String tenantId,
                                                  @RequestParam String clusterId) {
        AccessContext ctx = accessResolver.resolveNamespacedAccess(tenantId, clusterId, namespace);
        return new ResponseData<>(operationsFactory
                .getNamespaceStatOperation(clusterId, ctx.tenantId(), ctx.adminMode())
                .row(namespace));
    }
}
