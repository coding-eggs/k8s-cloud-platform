package com.coding.platformapi.controllers;

import com.coding.common.models.system.ResponseData;
import com.coding.data.models.k8s.PlatformAlertEvent;
import com.coding.platformapi.models.AlertQueryRequest;
import com.coding.platformapi.services.AlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 告警列表（用户身份）。
 *
 * <p>与 {@link AlertWebhookController} 共享 {@code /alert} 前缀 —— 方法路径不冲突即合法。
 * 租户收窄在 {@link AlertService}：token 带 tenantInfo 时只看本租户命名空间，
 * 没有租户 claim（平台管理员）才看全部。
 */
@Tag(name = "告警", description = "告警列表")
@RestController
@RequestMapping("/alert")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;

    @PostMapping("/list")
    @Operation(summary = "告警列表（租户身份自动收窄到本租户命名空间）")
    public ResponseData<List<PlatformAlertEvent>> list(@RequestBody AlertQueryRequest req) {
        return new ResponseData<>(alertService.list(req));
    }
}
