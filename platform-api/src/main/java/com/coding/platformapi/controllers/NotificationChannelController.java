package com.coding.platformapi.controllers;

import com.coding.common.models.system.ResponseData;
import com.coding.data.models.alert.NotificationChannel;
import com.coding.platformapi.models.NotificationChannelRequest;
import com.coding.platformapi.models.TenantRefRequest;
import com.coding.platformapi.security.AuthContext;
import com.coding.platformapi.services.NotificationChannelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 通知方式（平台管理面，仅管理员）。本层只做 HTTP 绑定与 {@link ResponseData} 包装。
 *
 * <p>权限行见 V2026_10_19_8：五个管理端点用 {@code platform:notifychannel:*}（只绑 admin）；
 * {@code /options} 用 {@code tenant:notifychannel:list} 并同时绑 admin —— 规则编辑页是租户上下文，
 * 平台管理员代管时也要能拿到候选。**六个端点都必须有行**，否则启动期交叉校验拒启。
 *
 * <p>列表与候选返回的 {@code config} 里**不含 {@code passwordEnc}**（service 层摘掉）。
 */
@Tag(name = "通知方式", description = "告警通知渠道：CRUD（当前只支持邮件）")
@RestController
@RequestMapping("/notification-channels")
@RequiredArgsConstructor
public class NotificationChannelController {

    private final NotificationChannelService channelService;
    private final AuthContext authContext;

    @Operation(summary = "通知方式列表", description = "全部渠道（全局 + 各租户），密码字段已摘除")
    @PostMapping("/list")
    public ResponseData<List<NotificationChannel>> list() {
        return new ResponseData<>(channelService.list());
    }

    @Operation(summary = "规则编辑页候选",
            description = "全局 + 本租户的**启用**渠道。token 带租户帽时以 token 为准；"
                    + "平台管理员代管态用请求体里的 tenantId，缺省时只返回全局渠道")
    @PostMapping("/options")
    public ResponseData<List<NotificationChannel>> options(
            @RequestBody(required = false) TenantRefRequest request) {
        String requested = request == null ? null : request.getTenantId();
        return new ResponseData<>(channelService.options(authContext.effectiveTenantId(requested)));
    }

    @Operation(summary = "新建通知方式", description = "code 必须 K8s label 合法且全局唯一；密码加密落库")
    @PostMapping
    public ResponseData<NotificationChannel> create(@RequestBody NotificationChannelRequest request) {
        return new ResponseData<>(channelService.create(request));
    }

    @Operation(summary = "修改通知方式", description = "code 不可改；密码留空表示沿用原密码")
    @PutMapping("/{code}")
    public ResponseData<NotificationChannel> update(@PathVariable("code") String code,
                                                    @RequestBody NotificationChannelRequest request) {
        return new ResponseData<>(channelService.update(code, request));
    }

    @Operation(summary = "删除通知方式", description = "仍被规则绑定时拒绝")
    @DeleteMapping("/{code}")
    public ResponseData<Void> delete(@PathVariable("code") String code) {
        channelService.delete(code);
        return new ResponseData<>();
    }
}
