package com.coding.platformapi.controllers;

import com.coding.common.models.system.ResponseData;
import com.coding.data.models.auth.PlatformPermission;
import com.coding.platformapi.models.PermissionKeyRequest;
import com.coding.platformapi.models.PermissionPointRequest;
import com.coding.platformapi.services.PermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限点目录 + CRUD 与热加载（list 鉴权 platform:role:read；写操作 platform:role:manage，
 * 见 V2026_09_27_1__permission_manage.sql 端点行）。
 * 本层只做 HTTP 绑定；读/写/热加载全在 {@link PermissionService}（分层约定见 docs/development/backend-layering.md）。
 */
@Tag(name = "权限点管理", description = "权限点目录 + CRUD（写前裸端点校验）+ 运行时规则热加载")
@RestController
@RequestMapping("/permission")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;

    @Operation(summary = "权限点目录")
    @PostMapping("/list")
    public ResponseData<List<PlatformPermission>> list() {
        return new ResponseData<>(permissionService.list());
    }

    @Operation(summary = "新建权限点（写前裸端点校验，提交后热加载即时生效）")
    @PostMapping("/create")
    public ResponseData<PlatformPermission> create(@RequestBody PermissionPointRequest r) {
        return new ResponseData<>(permissionService.create(r));
    }

    @Operation(summary = "编辑权限点（全量重存该行）")
    @PostMapping("/update")
    public ResponseData<Void> update(@RequestBody PermissionPointRequest r) {
        permissionService.update(r);
        return new ResponseData<>();
    }

    @Operation(summary = "删除权限点（软删；端点唯一覆盖行被裸端点保护拒绝）")
    @PostMapping("/delete")
    public ResponseData<Void> delete(@RequestBody PermissionKeyRequest r) {
        permissionService.delete(r.getId());
        return new ResponseData<>();
    }

    @Operation(summary = "重载运行时授权规则（SQL 直接改库后的对齐入口；CRUD 本身自动生效）")
    @PostMapping("/reload")
    public ResponseData<Void> reload() {
        permissionService.reload();
        return new ResponseData<>();
    }
}
