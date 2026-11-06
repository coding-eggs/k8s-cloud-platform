package com.coding.platformapi.controllers;

import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.models.overview.PlatformOverviewView;
import com.coding.platformapi.services.overview.PlatformOverviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 平台总览页（一页一 controller）。权限码 {@code platform:overview:view}（见 V2026_11_06_4）。 */
@Tag(name = "平台总览")
@RestController
@RequestMapping("/overview")
@RequiredArgsConstructor
public class PlatformOverviewController {

    private final PlatformOverviewService overviewService;

    @PostMapping("/platform")
    @Operation(summary = "平台总览", description = "全部可用集群按资源类型汇总 + 平台服务健康 + 租户；不可用集群只计数、不计入 k8s 侧合计")
    public ResponseData<PlatformOverviewView> platform() {
        return new ResponseData<>(overviewService.overview());
    }
}
