package com.coding.platformapi.controllers;

import com.coding.common.models.system.ResponseData;
import com.coding.data.models.system.PlatformApplicationOperation;
import com.coding.platformapi.audit.AuditAction;
import com.coding.platformapi.audit.Auditable;
import com.coding.platformapi.models.ApplicationCreateRequest;
import com.coding.platformapi.models.ApplicationDeleteRequest;
import com.coding.platformapi.models.ApplicationDeployRequest;
import com.coding.platformapi.models.ApplicationOptionsView;
import com.coding.platformapi.models.ApplicationPreview;
import com.coding.platformapi.models.ApplicationPreviewRequest;
import com.coding.platformapi.models.ApplicationQueryRequest;
import com.coding.platformapi.models.ApplicationRevisionView;
import com.coding.platformapi.models.ApplicationUpdateRequest;
import com.coding.platformapi.models.ApplicationView;
import com.coding.platformapi.security.AuthContext;
import com.coding.platformapi.services.ApplicationDeployService;
import com.coding.platformapi.services.ApplicationPreviewService;
import com.coding.platformapi.services.ApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 应用编排（画布）：应用图 / 预览 / 部署 / 版本。一页一 controller。 */
@Tag(name = "应用编排", description = "应用图 / 预览 / 部署 / 版本")
@RestController
@RequestMapping("/application")
public class ApplicationController {

    private final ApplicationService service;
    private final ApplicationPreviewService preview;
    private final ApplicationDeployService deploy;

    @Autowired
    private AuthContext auth;

    public ApplicationController(ApplicationService service, ApplicationPreviewService preview,
                                 ApplicationDeployService deploy) {
        this.service = service;
        this.preview = preview;
        this.deploy = deploy;
    }

    @PostMapping("/list")
    @Operation(summary = "应用列表（租户按归属收窄；管理员不传 tenantId 看全部）")
    public ResponseData<List<ApplicationView>> list(@RequestBody ApplicationQueryRequest request) {
        return new ResponseData<>(service.list(request.getClusterId(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/get")
    @Operation(summary = "应用详情（含当前 spec 与版本号）")
    public ResponseData<ApplicationView> get(@RequestBody ApplicationQueryRequest request) {
        return new ResponseData<>(service.get(request.getAppId(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/create")
    @Auditable(action = AuditAction.APPLICATION_CREATE, target = "#request.name")
    @Operation(summary = "创建应用（只落 spec，不部署）")
    public ResponseData<ApplicationView> create(@RequestBody ApplicationCreateRequest request) {
        return new ResponseData<>(service.create(request, operator(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/update")
    @Auditable(action = AuditAction.APPLICATION_UPDATE, target = "#request.appId")
    @Operation(summary = "编辑应用（部署进行中拒绝）")
    public ResponseData<ApplicationView> update(@RequestBody ApplicationUpdateRequest request) {
        return new ResponseData<>(service.update(request, operator()));
    }

    @PostMapping("/delete")
    @Auditable(action = AuditAction.APPLICATION_DELETE, target = "#request.appId")
    @Operation(summary = "删除应用（先删集群对象，PVC 保留）")
    public ResponseData<Void> delete(@RequestBody ApplicationDeleteRequest request) {
        service.delete(request, operator());
        return new ResponseData<>();
    }

    @PostMapping("/preview")
    @Operation(summary = "预览：渲染 + 与上一版本的差异（不写集群）")
    public ResponseData<ApplicationPreview> preview(@RequestBody ApplicationPreviewRequest request) {
        return new ResponseData<>(preview.preview(request,
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/deploy")
    @Auditable(action = AuditAction.APPLICATION_DEPLOY, target = "#request.appId")
    @Operation(summary = "部署 / 升级 / 重放历史版本（异步，返回操作 id）")
    public ResponseData<Long> deploy(@RequestBody ApplicationDeployRequest request) {
        return new ResponseData<>(deploy.start(request.getAppId(), request.getRevision(), operator()));
    }

    @PostMapping("/operation/get")
    @Operation(summary = "操作轮询（阶段 + 每节点状态）")
    public ResponseData<PlatformApplicationOperation> operation(@RequestBody ApplicationQueryRequest request) {
        return new ResponseData<>(deploy.operation(request.getOperationId()));
    }

    @PostMapping("/revision/list")
    @Operation(summary = "版本历史")
    public ResponseData<List<ApplicationRevisionView>> revisions(@RequestBody ApplicationQueryRequest request) {
        return new ResponseData<>(service.revisions(request.getAppId()));
    }

    @PostMapping("/options")
    @Operation(summary = "画布可选项（节点类型 / 命名空间）")
    public ResponseData<ApplicationOptionsView> options(@RequestBody ApplicationQueryRequest request) {
        return new ResponseData<>(service.options(request.getClusterId(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    private String operator() {
        var me = auth.current();
        return me != null ? me.getUsername() : "unknown";
    }
}
