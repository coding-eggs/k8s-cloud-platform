package com.coding.platformapi.controllers;

import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.audit.AuditAction;
import com.coding.platformapi.audit.Auditable;
import com.coding.platformapi.models.RegistryProjectRequest;
import com.coding.platformapi.models.RegistryProjectView;
import com.coding.platformapi.services.HarborRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Harbor 的租户镜像项目管理（**平台管理员**）。与 {@code RegistryController} 分开：
 * 那三个是租户只读入口、**有意不标审计**（有结构性钉子钉着）；这三个是写操作，必须留痕。
 *
 * <p>权限行见迁移 {@code V2026_10_27_1}：list → {@code platform:registry:project:read}，
 * sync / rebuild → {@code platform:registry:project:manage}。
 */
@Tag(name = "镜像仓库-租户项目", description = "Harbor 的租户 project / robot 账号 / 凭据同步（平台管理员）")
@RestController
@RequestMapping("/registry/projects")
@RequiredArgsConstructor
public class RegistryProjectController {

    private final HarborRegistryService harborRegistry;

    @Operation(summary = "租户镜像项目列表",
            description = "管理员视图：每个租户/平台共享在集群 Harbor 上的 project、凭据与上次同步结果")
    @PostMapping("/list")
    public ResponseData<List<RegistryProjectView>> projects(@RequestBody RegistryProjectRequest request) {
        return new ResponseData<>(harborRegistry.list(request.getClusterId()));
    }

    @Operation(summary = "同步全部租户的 Harbor 项目",
            description = "全量对账：缺的补、host 变了的重建；返回逐租户结果（含失败原因）")
    @PostMapping("/sync")
    @Auditable(action = AuditAction.REGISTRY_SYNC_PROJECTS, target = "#request.clusterId")
    public ResponseData<HarborRegistryService.SyncResult> syncProjects(
            @RequestBody RegistryProjectRequest request) {
        return new ResponseData<>(harborRegistry.ensureAll(request.getClusterId()));
    }

    @Operation(summary = "重建某个租户的 Harbor 机器人账号",
            description = "tenantId 为空 = 平台共享项目。先删旧 robot 再建新的，凭据密文同步覆盖")
    @PostMapping("/rebuild")
    @Auditable(action = AuditAction.REGISTRY_REBUILD_PROJECT, target = "#request.clusterId")
    public ResponseData<HarborRegistryService.SyncItem> rebuildProject(
            @RequestBody RegistryProjectRequest request) {
        return new ResponseData<>(harborRegistry.rebuild(request.getClusterId(), request.getTenantId()));
    }
}
