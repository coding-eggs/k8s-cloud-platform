package com.coding.platformapi.controllers;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.models.NotificationUserView;
import com.coding.platformapi.models.TenantRefRequest;
import com.coding.platformapi.security.AuthContext;
import com.coding.platformapi.services.NotificationUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 规则编辑页的收件人候选（本租户用户 + 邮箱）。权限行 {@code tenant:notificationuser:list}，
 * 同时绑 admin 与 tenant-admin。
 *
 * <p>租户口径与 {@code NotificationChannelController#options} 一致：token 带租户帽时以 token 为准，
 * 平台管理员代管态用请求体里的 tenantId；两者都没有就报错（**不猜一个租户**）。
 */
@Tag(name = "通知收件人", description = "本租户用户候选（id / 显示名 / 邮箱）")
@RestController
@RequestMapping("/notification-users")
@RequiredArgsConstructor
public class NotificationUserController {

    private final NotificationUserService userService;
    private final AuthContext authContext;

    @Operation(summary = "本租户用户候选", description = "email 可能为空 —— 前端要给「无邮箱」提示")
    @PostMapping("/list")
    public ResponseData<List<NotificationUserView>> list(
            @RequestBody(required = false) TenantRefRequest request) {
        String tenantId = authContext.effectiveTenantId(request == null ? null : request.getTenantId());
        if (!StringUtils.hasText(tenantId)) {
            throw new CloudPlatformException(EnumResponseType.NOTIFY_CHANNEL_INVALID, "缺少租户上下文");
        }
        return new ResponseData<>(userService.listByTenant(tenantId));
    }
}
