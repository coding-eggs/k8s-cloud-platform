package com.coding.platformapi.controllers;

import com.coding.common.models.system.ResponseData;
import com.coding.data.models.auth.PlatformRole;
import com.coding.platformapi.models.RoleCreateRequest;
import com.coding.platformapi.models.RoleKeyRequest;
import com.coding.platformapi.models.RolePermissionSaveRequest;
import com.coding.platformapi.services.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色管理（鉴权走表驱动 PermissionAuthorizationManager：写操作 platform:role:manage，
 * 读操作 platform:role:read；见 V2026_09_24_2__rbac_seed.sql 端点行）
 */
@Tag(name = "角色管理", description = "角色列表/创建/删除 + 权限勾选保存与回显")
@RestController
@RequestMapping("/role")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @Operation(summary = "角色列表")
    @PostMapping("/list")
    public ResponseData<List<PlatformRole>> list() {
        return new ResponseData<>(roleService.list());
    }

    @Operation(summary = "创建角色")
    @PostMapping("/create")
    public ResponseData<PlatformRole> create(@RequestBody RoleCreateRequest r) {
        return new ResponseData<>(roleService.create(r));
    }

    @Operation(summary = "删除角色（内置角色拒绝）")
    @PostMapping("/delete")
    public ResponseData<Void> delete(@RequestBody RoleKeyRequest r) {
        roleService.delete(r.getId());
        return new ResponseData<>();
    }

    @Operation(summary = "保存角色权限（全量重存，scope 不变量校验）")
    @PostMapping("/permission/save")
    public ResponseData<Void> save(@RequestBody RolePermissionSaveRequest r) {
        roleService.savePermissions(r.getRoleId(), r.getPermissionCodes());
        return new ResponseData<>();
    }

    @Operation(summary = "查看角色已勾选权限 code（配置页回显）")
    @PostMapping("/permission/list")
    public ResponseData<List<String>> permsOf(@RequestBody RoleKeyRequest r) {
        return new ResponseData<>(roleService.permissionCodesOf(r.getId()));
    }
}
