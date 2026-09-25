package com.coding.platformapi.controllers;

import com.coding.common.models.system.ResponseData;
import com.coding.data.models.auth.PlatformUser;
import com.coding.platformapi.models.UserCreateRequest;
import com.coding.platformapi.models.UserKeyRequest;
import com.coding.platformapi.models.UserRoleGrantRequest;
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
 * 用户管理（鉴权走表驱动 PermissionAuthorizationManager，platform:user:manage）
 */
@Tag(name = "用户管理", description = "平台用户创建/查询 + 平台域角色授予/回收")
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

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
}
