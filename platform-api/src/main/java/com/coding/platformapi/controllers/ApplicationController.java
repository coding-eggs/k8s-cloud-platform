package com.coding.platformapi.controllers;

import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.audit.AuditAction;
import com.coding.platformapi.audit.Auditable;
import com.coding.platformapi.models.AppDependencyRequest;
import com.coding.platformapi.models.AppExecutionView;
import com.coding.platformapi.models.AppOverviewView;
import com.coding.platformapi.models.AppReleasePreviewView;
import com.coding.platformapi.models.AppReleaseRequest;
import com.coding.platformapi.models.AppReleaseView;
import com.coding.platformapi.models.AppServiceRequest;
import com.coding.platformapi.models.AppServiceView;
import com.coding.platformapi.services.AppDependencyService;
import com.coding.platformapi.services.AppReleaseService;
import com.coding.platformapi.services.AppServiceConfigService;
import com.coding.platformapi.models.ApplicationCreateRequest;
import com.coding.platformapi.models.ApplicationDeleteRequest;
import com.coding.platformapi.models.ApplicationOptionsView;
import com.coding.platformapi.models.ApplicationQueryRequest;
import com.coding.platformapi.models.ApplicationView;
import com.coding.platformapi.security.AuthContext;
import com.coding.platformapi.services.ApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 应用：台账、服务、依赖、发布与执行。一页一 controller。 */
@Tag(name = "应用", description = "应用台账 / 服务 / 依赖 / 发布 / 执行")
@RestController
@RequestMapping("/application")
public class ApplicationController {

    private final ApplicationService service;

    @Autowired
    private AuthContext auth;

    private final AppServiceConfigService serviceConfig;
    private final AppReleaseService release;
    private final AppDependencyService dependency;

    public ApplicationController(ApplicationService service, AppServiceConfigService serviceConfig,
                                 AppReleaseService release, AppDependencyService dependency) {
        this.service = service;
        this.serviceConfig = serviceConfig;
        this.release = release;
        this.dependency = dependency;
    }

    @PostMapping("/list")
    @Operation(summary = "应用列表（租户按归属收窄；管理员不传 tenantId 看全部）")
    public ResponseData<List<ApplicationView>> list(@RequestBody ApplicationQueryRequest request) {
        return new ResponseData<>(service.list(request.getClusterId(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/get")
    @Operation(summary = "应用详情（台账字段与线上发布号）")
    public ResponseData<ApplicationView> get(@RequestBody ApplicationQueryRequest request) {
        return new ResponseData<>(service.get(request.getAppId(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/create")
    @Auditable(action = AuditAction.APPLICATION_CREATE, target = "#request.name")
    @Operation(summary = "创建应用（只建台账，不部署）")
    public ResponseData<ApplicationView> create(@RequestBody ApplicationCreateRequest request) {
        return new ResponseData<>(service.create(request, operator(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/delete")
    @Auditable(action = AuditAction.APPLICATION_DELETE, target = "#request.appId")
    @Operation(summary = "删除应用（先删集群对象，PVC 保留）")
    public ResponseData<Void> delete(@RequestBody ApplicationDeleteRequest request) {
        service.delete(request, operator(), auth.effectiveTenantId(request.getTenantId()));
        return new ResponseData<>();
    }

    @PostMapping("/options")
    @Operation(summary = "新建应用的可选项（命名空间）")
    public ResponseData<ApplicationOptionsView> options(@RequestBody ApplicationQueryRequest request) {
        return new ResponseData<>(service.options(request.getClusterId(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    // ---- 服务、依赖、发布与执行 ----

    @PostMapping("/service/create")
    @Auditable(action = AuditAction.APPLICATION_SERVICE_CREATE, target = "#request.appId + '/' + #request.serviceKey")
    @Operation(summary = "添加服务（只写期望配置，不发布）")
    public ResponseData<AppServiceView> serviceCreate(@RequestBody AppServiceRequest request) {
        return new ResponseData<>(serviceConfig.create(request, auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/service/update")
    @Auditable(action = AuditAction.APPLICATION_SERVICE_UPDATE, target = "#request.appId + '/' + #request.serviceKey")
    @Operation(summary = "修改服务期望配置（version 乐观锁；key 不可改）")
    public ResponseData<AppServiceView> serviceUpdate(@RequestBody AppServiceRequest request) {
        return new ResponseData<>(serviceConfig.update(request, auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/service/remove")
    @Auditable(action = AuditAction.APPLICATION_SERVICE_REMOVE, target = "#request.appId + '/' + #request.serviceKey")
    @Operation(summary = "移除服务（已发布过的标记待删除，下次发布勾选后生效）")
    public ResponseData<Void> serviceRemove(@RequestBody AppServiceRequest request) {
        serviceConfig.remove(request, auth.effectiveTenantId(request.getTenantId()));
        return new ResponseData<>();
    }

    @PostMapping("/dependency/add")
    @Auditable(action = AuditAction.APPLICATION_DEPENDENCY_ADD, target = "#request.appId + '/' + #request.key")
    @Operation(summary = "添加依赖：新建 / 绑定中间件实例，或登记外部连接（只收 Secret 引用，不收明文口令）")
    public ResponseData<AppOverviewView.DependencyRow> dependencyAdd(@RequestBody AppDependencyRequest request) {
        return new ResponseData<>(dependency.add(request, operator(), auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/dependency/update")
    @Auditable(action = AuditAction.APPLICATION_DEPENDENCY_UPDATE, target = "#request.appId + '/' + #request.key")
    @Operation(summary = "修改外部连接（地址 / 端口 / Secret 登记；依赖名与协议不可改；引用方下次发布生效）")
    public ResponseData<AppOverviewView.DependencyRow> dependencyUpdate(@RequestBody AppDependencyRequest request) {
        return new ResponseData<>(dependency.update(request, auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/dependency/remove")
    @Auditable(action = AuditAction.APPLICATION_DEPENDENCY_REMOVE, target = "#request.appId + '/' + #request.key")
    @Operation(summary = "移除依赖（仍被引用时拒绝；CREATED 实例可选删除，默认保留）")
    public ResponseData<Void> dependencyRemove(@RequestBody AppDependencyRequest request) {
        dependency.remove(request, operator(), auth.effectiveTenantId(request.getTenantId()));
        return new ResponseData<>();
    }

    @PostMapping("/dependency/init")
    @Auditable(action = AuditAction.APPLICATION_DEPENDENCY_INIT, target = "#request.appId + '/' + #request.key")
    @Operation(summary = "手动触发依赖初始化（实例就绪后；异步，状态与日志见 overview）")
    public ResponseData<Void> dependencyInit(@RequestBody AppDependencyRequest request) {
        dependency.init(request, operator(), auth.effectiveTenantId(request.getTenantId()));
        return new ResponseData<>();
    }

    @PostMapping("/overview")
    @Operation(summary = "应用概览：服务现读状态、依赖、待发布与执行进度")
    public ResponseData<AppOverviewView> overview(@RequestBody AppReleaseRequest request) {
        return new ResponseData<>(release.overview(request.getAppId(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/release/preview")
    @Operation(summary = "发布预览：待发布改动、需一并勾选的服务、对象增删（不写集群）")
    public ResponseData<AppReleasePreviewView> releasePreview(@RequestBody AppReleaseRequest request) {
        return new ResponseData<>(release.preview(request.getAppId(), request.getServiceKeys(),
                request.getImages(), auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/release/create")
    @Auditable(action = AuditAction.APPLICATION_RELEASE_CREATE, target = "#request.appId")
    @Operation(summary = "创建发布（异步执行，返回执行 id）")
    public ResponseData<Long> releaseCreate(@RequestBody AppReleaseRequest request) {
        return new ResponseData<>(release.create(request.getAppId(), request.getServiceKeys(), request.getImages(),
                request.getNote(), operator(), auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/release/rollback")
    @Auditable(action = AuditAction.APPLICATION_RELEASE_ROLLBACK,
            target = "#request.appId + '@' + #request.releaseNo")
    @Operation(summary = "回滚：以历史发布的快照生成一条新发布（异步执行，返回执行 id）")
    public ResponseData<Long> releaseRollback(@RequestBody AppReleaseRequest request) {
        return new ResponseData<>(release.rollback(request.getAppId(), request.getReleaseNo(), request.getNote(),
                operator(), auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/release/list")
    @Operation(summary = "发布记录（新到旧）")
    public ResponseData<List<AppReleaseView>> releaseList(@RequestBody AppReleaseRequest request) {
        return new ResponseData<>(release.list(request.getAppId(), auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/release/get")
    @Operation(summary = "发布详情（快照 + 对象清单）")
    public ResponseData<AppReleaseView> releaseGet(@RequestBody AppReleaseRequest request) {
        return new ResponseData<>(release.get(request.getAppId(), request.getReleaseNo(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/execution/active")
    @Operation(summary = "当前活动执行（无则 data 为空）")
    public ResponseData<AppExecutionView> executionActive(@RequestBody AppReleaseRequest request) {
        return new ResponseData<>(release.activeExecution(request.getAppId(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/execution/resume")
    @Auditable(action = AuditAction.APPLICATION_EXECUTION_RESUME, target = "#request.executionId")
    @Operation(summary = "继续失败或中断的执行（用当前登录身份接着跑）")
    public ResponseData<Void> executionResume(@RequestBody AppReleaseRequest request) {
        release.resume(request.getExecutionId(), operator(), auth.effectiveTenantId(request.getTenantId()));
        return new ResponseData<>();
    }

    private String operator() {
        var me = auth.current();
        return me != null ? me.getUsername() : "unknown";
    }
}
