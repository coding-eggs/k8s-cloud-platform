package com.coding.platformapi.controllers;

import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.audit.AuditAction;
import com.coding.platformapi.audit.Auditable;
import com.coding.platformapi.models.RegistryProjectRequest;
import com.coding.platformapi.models.RegistryProjectView;
import com.coding.platformapi.security.AuthContext;
import com.coding.platformapi.services.HarborRegistryService;
import com.coding.platformapi.services.RegistryPullSecretService;
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

    /** 命名空间默认拉取凭据的重下发（spec 2026-10-31 §6.6） */
    private final RegistryPullSecretService pullSecrets;

    /** 租户帽：list 按它收窄（同步 / 重建仍只给管理员） */
    private final AuthContext auth;

    @Operation(summary = "租户镜像项目列表",
            description = "每个租户/平台共享在集群 Harbor 上的 project、凭据与上次同步结果。"
                    + "租户只看到自己那几行（V2026_10_29_4 起对租户开放）")
    @PostMapping("/list")
    public ResponseData<List<RegistryProjectView>> projects(@RequestBody RegistryProjectRequest request) {
        return new ResponseData<>(harborRegistry.list(request.getClusterId(), auth.hatTenantId()));
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

    /**
     * 重下发该集群**所有**命名空间的默认拉取凭据（spec 2026-10-31 §6.6）。
     *
     * <p>为什么需要它：存量命名空间不会因为升级就拥有那个 Secret —— 命名空间是集群里的对象，SQL 碰不到
     * 它们。第一次上线、robot 轮换后没自动重下发成功、Secret 被误删，都靠它补。
     *
     * <p>只覆盖分配表里存在的命名空间，不去扫集群全部 ns：kube-system 这类塞进去是噪音。
     */
    @Operation(summary = "重下发全部命名空间的默认拉取凭据",
            description = "把每个租户的 registry 凭据写进其各命名空间的 platform-registry-pull，并挂到 default SA。"
                    + "返回成功/失败数与失败明细。存量 Pod 需重建才生效。")
    @PostMapping("/pull-secret/sync")
    @Auditable(action = AuditAction.REGISTRY_SYNC_PULL_SECRET, target = "#request.clusterId")
    public ResponseData<RegistryPullSecretService.ResyncResult> syncPullSecrets(
            @RequestBody RegistryProjectRequest request) {
        return new ResponseData<>(pullSecrets.resyncCluster(request.getClusterId()));
    }
}
