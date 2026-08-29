package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.k8s.dto.NamespaceDTO;
import com.coding.common.models.k8s.dto.admin.AdminClusterKeyRequest;
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

import java.util.List;

/**
 * 平台管理域 - 命名空间（/admin/namespace/**）：创建 / 列表 / 删除。
 * 全部走 admin client；权限由 SecurityFilterChain 统一要求 PLATFORM:admin（不透传租户上下文）。
 * 分配 / 取消分配的编排（ns + ClusterRole + SA + RoleBinding）在 platform-api 侧完成。
 */
@Tag(name = "平台管理域-命名空间", description = "命名空间创建 / 列表 / 删除")
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

    @PostMapping("/list")
    @Operation(summary = "集群命名空间列表", description = "返回集群内已有命名空间全字段对象（按名字升序；展示裁剪由 platform-api 侧决定）")
    public ResponseData<List<NamespaceDTO>> list(@RequestBody AdminClusterKeyRequest req) {
        return new ResponseData<>(provisioning.listNamespaces(req.getClusterId()));
    }

    @PostMapping("/delete")
    @Operation(summary = "删除命名空间", description = "删 ns 本身（能否删除由 platform-api 侧业务规则判定）")
    public ResponseData<Void> delete(@RequestBody AdminNamespaceKeyRequest req) {
        provisioning.deleteNamespace(req.getClusterId(), req.getNamespace());
        return new ResponseData<>();
    }

}
