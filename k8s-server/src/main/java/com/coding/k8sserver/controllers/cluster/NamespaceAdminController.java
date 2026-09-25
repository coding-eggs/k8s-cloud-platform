package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.k8s.dto.admin.AdminNamespaceKeyRequest;
import com.coding.common.models.system.ResponseData;
import com.coding.k8sserver.services.K8sProvisioningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台管理域 - 命名空间（/admin/namespace/create）：幂等建 ns（provisioning / 分配流程用）。
 * 权限由 SecurityFilterChain 统一要求 PLATFORM:admin（不透传租户上下文）。
 * 命名空间的列 / 取 / 建 / 改 / 删 / yaml 走一等公民边界 /admin/namespaces，不在本类。
 */
@Tag(name = "平台管理域-命名空间", description = "命名空间幂等创建")
@RestController
@RequestMapping("/admin/namespace")
@RequiredArgsConstructor
public class NamespaceAdminController {

    private final K8sProvisioningService provisioning;

    @PostMapping("/create")
    @Operation(summary = "创建命名空间（幂等）", description = "不存在则创建并打 managed-by 标签；已存在直接返回")
    public ResponseData<Void> create(@RequestBody AdminNamespaceKeyRequest req) {
        provisioning.ensureNamespace(req.getClusterId(), req.getNamespace());
        return new ResponseData<>();
    }

}
