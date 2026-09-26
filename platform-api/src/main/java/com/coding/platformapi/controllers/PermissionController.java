package com.coding.platformapi.controllers;

import com.coding.common.models.system.ResponseData;
import com.coding.data.mapper.auth.PlatformPermissionMapper;
import com.coding.data.models.auth.PlatformPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限目录（配置页勾选项数据源；鉴权 platform:role:read）
 */
@Tag(name = "权限目录", description = "全部有效权限点（按 code 聚合渲染复选框）")
@RestController
@RequestMapping("/permission")
@RequiredArgsConstructor
public class PermissionController {

    private final PlatformPermissionMapper permissionMapper;

    @Operation(summary = "权限点目录")
    @PostMapping("/list")
    public ResponseData<List<PlatformPermission>> list() {
        return new ResponseData<>(permissionMapper.selectAllActive());
    }
}
