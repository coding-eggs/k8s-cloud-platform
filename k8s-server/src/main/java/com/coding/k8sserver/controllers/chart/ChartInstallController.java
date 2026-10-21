package com.coding.k8sserver.controllers.chart;

import com.coding.common.models.k8s.dto.ChartApplyRequestDTO;
import com.coding.common.models.k8s.dto.ChartApplyResultDTO;
import com.coding.common.models.k8s.dto.ChartInspectRequestDTO;
import com.coding.common.models.k8s.dto.ChartInspectResultDTO;
import com.coding.common.models.k8s.dto.ChartPreviewRequestDTO;
import com.coding.common.models.k8s.dto.ChartPreviewResultDTO;
import com.coding.common.models.k8s.dto.ChartUninstallRequestDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8sserver.chart.ChartInstallService;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import com.coding.k8sserver.components.ResourceAccessResolver;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用商店执行链（**PLATFORM 边界**）。授权在 platform-api（tenant:app:install / platform:app:manage），
 * 本层只声明边界并执行。
 */
@Tag(name = "应用商店-执行", description = "chart 渲染/预览/安装/卸载（helm）")
@RestController
@RequestMapping("/chart")
public class ChartInstallController implements AccessBoundaryAware {

    private final ChartInstallService service;
    private final ResourceAccessResolver accessResolver;

    public ChartInstallController(ChartInstallService service, ResourceAccessResolver accessResolver) {
        this.service = service;
        this.accessResolver = accessResolver;
    }

    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.PLATFORM;
    }

    @PostMapping("/inspect")
    public ResponseData<ChartInspectResultDTO> inspect(@RequestBody ChartInspectRequestDTO request) {
        return new ResponseData<>(service.inspect(request));
    }

    @PostMapping("/preview")
    public ResponseData<ChartPreviewResultDTO> preview(@RequestBody ChartPreviewRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        return new ResponseData<>(service.preview(request));
    }

    @PostMapping("/apply")
    public ResponseData<ChartApplyResultDTO> apply(@RequestBody ChartApplyRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        return new ResponseData<>(service.apply(request));
    }

    @PostMapping("/uninstall")
    public ResponseData<Boolean> uninstall(@RequestBody ChartUninstallRequestDTO request) {
        accessResolver.assertClusterAccess(request.getClusterId());
        return new ResponseData<>(service.uninstall(request));
    }
}
