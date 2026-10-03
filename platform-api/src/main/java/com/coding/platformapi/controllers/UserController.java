package com.coding.platformapi.controllers;

import com.coding.common.models.system.ResponseData;
import com.coding.data.models.auth.PlatformRole;
import com.coding.data.models.auth.PlatformTenant;
import com.coding.data.models.auth.PlatformUser;
import com.coding.data.models.system.TokenUserInfo;
import com.coding.platformapi.models.UserCreateRequest;
import com.coding.platformapi.models.UserKeyRequest;
import com.coding.platformapi.models.UserRoleGrantRequest;
import com.coding.platformapi.services.CurrentUserQuery;
import com.coding.platformapi.services.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户管理（鉴权走表驱动 PermissionAuthorizationManager，platform:user:manage）。
 * /me 与 /my-tenants 为登录即接口（ExemptPaths 豁免权限点，仅需 authenticated），供 SPA 引导权限与租户切换器。
 */
@Tag(name = "用户管理", description = "平台用户创建/查询 + 平台域角色授予/回收 + 当前用户上下文")
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final CurrentUserQuery currentUserQuery;

    @PostMapping("/me")
    @Operation(summary = "当前用户信息", description = "登录即可，无需权限点；返回 token 上下文（含 permissions/platformRoles/tenantInfo），供前端引导")
    public ResponseData<TokenUserInfo> me() {
        return new ResponseData<>(currentUserQuery.me());
    }

    @PostMapping("/my-tenants")
    @Operation(summary = "我所属的租户", description = "登录即可，无需权限点；返回未删除租户简表，供租户切换器")
    public ResponseData<List<PlatformTenant>> myTenants() {
        return new ResponseData<>(currentUserQuery.myTenants());
    }

    @PostMapping("/create")
    @Operation(summary = "创建用户", description = "用户名唯一，密码 BCrypt 加密存储；type=TENANT_USER、source=LOCAL")
    public ResponseData<PlatformUser> create(@RequestBody UserCreateRequest request) {
        return new ResponseData<>(userService.create(request));
    }

    @PostMapping("/list")
    @Operation(summary = "用户列表", description = "全部未删除用户")
    public ResponseData<List<PlatformUser>> list() {
        return new ResponseData<>(userService.list());
    }

    @PostMapping("/get")
    @Operation(summary = "用户详情")
    public ResponseData<PlatformUser> get(@RequestBody UserKeyRequest request) {
        return new ResponseData<>(userService.get(request.getId()));
    }

    @PostMapping("/platformRole/grant")
    @Operation(summary = "授予平台角色", description = "uk_user_role 唯一索引兜幂等，重复授予不报错")
    public ResponseData<Void> grant(@RequestBody UserRoleGrantRequest request) {
        userService.grantPlatformRole(request.getUserId(), request.getRoleId());
        return new ResponseData<>();
    }

    @PostMapping("/platformRole/revoke")
    @Operation(summary = "回收平台角色")
    public ResponseData<Void> revoke(@RequestBody UserRoleGrantRequest request) {
        userService.revokePlatformRole(request.getUserId(), request.getRoleId());
        return new ResponseData<>();
    }

    @PostMapping("/platformRole/list")
    @Operation(summary = "用户已持平台角色", description = "回显：仅启用 + 未删除 + PLATFORM 族；供用户管理对话框展示当前持有与授予/回收操作")
    public ResponseData<List<PlatformRole>> platformRoleList(@RequestBody UserKeyRequest request) {
        return new ResponseData<>(userService.platformRolesOf(request.getId()));
    }
}
