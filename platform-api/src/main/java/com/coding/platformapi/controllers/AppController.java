package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.ChartPreviewResultDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.data.models.system.PlatformApp;
import com.coding.data.models.system.PlatformAppOperation;
import com.coding.platformapi.audit.AuditAction;
import com.coding.platformapi.audit.Auditable;
import com.coding.platformapi.models.AppInstallRequest;
import com.coding.platformapi.models.AppOperationQuery;
import com.coding.platformapi.models.AppPreviewRequest;
import com.coding.platformapi.models.AppQueryRequest;
import com.coding.platformapi.models.AppUninstallRequest;
import com.coding.platformapi.models.AppUpgradeRequest;
import com.coding.platformapi.security.AuthContext;
import com.coding.platformapi.services.AppInstallService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 应用商店（计划①：管理员安装/升级/卸载）。一页一 controller。 */
@Tag(name = "应用商店", description = "应用安装/升级/卸载与操作记录")
@RestController
@RequestMapping("/app")
public class AppController {

    private final AppInstallService service;

    @Autowired
    private AuthContext auth;

    public AppController(AppInstallService service) {
        this.service = service;
    }

    @PostMapping("/install")
    @Auditable(action = AuditAction.APP_INSTALL, target = "#request.clusterId")
    @Operation(summary = "安装应用（异步；返回操作 id）")
    public ResponseData<Long> install(@RequestBody AppInstallRequest request) {
        return new ResponseData<>(service.install(request, operator()));
    }

    @PostMapping("/upgrade")
    @Auditable(action = AuditAction.APP_UPGRADE, target = "#request.appId")
    @Operation(summary = "升级应用到目标版本（异步）")
    public ResponseData<Long> upgrade(@RequestBody AppUpgradeRequest request) {
        return new ResponseData<>(service.upgrade(request, operator()));
    }

    @PostMapping("/uninstall")
    @Auditable(action = AuditAction.APP_UNINSTALL, target = "#request.appId")
    @Operation(summary = "卸载应用（异步；CRD 与 PVC 不删）")
    public ResponseData<Long> uninstall(@RequestBody AppUninstallRequest request) {
        return new ResponseData<>(service.uninstall(request, operator()));
    }

    @PostMapping("/list")
    @Operation(summary = "按集群/命名空间列出应用")
    public ResponseData<List<PlatformApp>> list(@RequestBody AppQueryRequest request) {
        return new ResponseData<>(service.list(request.getClusterId(), request.getNamespace()));
    }

    @PostMapping("/get")
    @Operation(summary = "应用详情")
    public ResponseData<PlatformApp> get(@RequestBody AppQueryRequest request) {
        return new ResponseData<>(service.getApp(request.getAppId()));
    }

    @PostMapping("/preview")
    @Operation(summary = "渲染预览（不落盘；返回将创建的对象与阻断项）")
    public ResponseData<ChartPreviewResultDTO> preview(@RequestBody AppPreviewRequest request) {
        return new ResponseData<>(service.preview(request));
    }

    @PostMapping("/operations/get")
    @Operation(summary = "查询一条应用操作（轮询）")
    public ResponseData<PlatformAppOperation> operation(@RequestBody AppOperationQuery request) {
        return new ResponseData<>(service.get(request.getOperationId()));
    }

    private String operator() {
        var me = auth.current();
        return me != null ? me.getUsername() : "unknown";
    }
}
