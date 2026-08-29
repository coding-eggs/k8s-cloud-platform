package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.k8s.dto.admin.AdminCleanupRequest;
import com.coding.common.models.k8s.dto.admin.AdminSaEnsureRequest;
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
 * 平台管理域 - 租户 K8s 侧生命周期（/admin/tenant/**）。
 * 全部走 admin client；权限由 SecurityFilterChain 统一要求 PLATFORM:admin（不透传租户上下文）。
 */
@Tag(name = "平台管理域-租户", description = "租户 SA 补建 / 租户 K8s 侧清理")
@RestController
@RequestMapping("/admin/tenant")
@RequiredArgsConstructor
public class TenantAdminController {

    private final K8sProvisioningService provisioning;

    @PostMapping("/sa/ensure")
    @Operation(summary = "补建单租户 SA（幂等）", description = "platform-system 下创建 tn-<serviceAccount>")
    public ResponseData<Void> ensureTenantSa(@RequestBody AdminSaEnsureRequest req) {
        provisioning.ensureTenantSa(req.getClusterId(), req.getServiceAccount());
        return new ResponseData<>();
    }

    @PostMapping("/cleanup")
    @Operation(summary = "租户 K8s 侧批量清理", description = "best-effort 删 RoleBinding + 各集群 SA，并清租户 client 缓存")
    public ResponseData<Void> cleanup(@RequestBody AdminCleanupRequest req) {
        provisioning.cleanupTenant(req);
        return new ResponseData<>();
    }

}
