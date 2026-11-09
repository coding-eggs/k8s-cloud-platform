package com.coding.platformapi.controllers;

import com.coding.common.models.page.PageResult;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.models.AppCanaryMetricsView;
import com.coding.platformapi.models.AppCanaryRequest;
import com.coding.platformapi.models.AppReleaseListRequest;
import com.coding.common.audit.AuditAction;
import com.coding.common.audit.Auditable;
import com.coding.platformapi.models.AppDependencyRequest;
import com.coding.platformapi.models.AppSettingsRequest;
import com.coding.platformapi.models.AppSettingsView;
import com.coding.platformapi.models.AppExecutionLogView;
import com.coding.platformapi.models.AppExecutionView;
import com.coding.platformapi.models.AppOverviewView;
import com.coding.platformapi.models.AppReleasePreviewView;
import com.coding.platformapi.models.AppReleaseRequest;
import com.coding.platformapi.models.AppReleaseView;
import com.coding.platformapi.models.AppServiceRequest;
import com.coding.platformapi.models.AppServiceView;
import com.coding.platformapi.services.AppCanaryService;
import com.coding.platformapi.services.AppDependencyService;
import com.coding.platformapi.services.AppExecutionLogService;
import com.coding.platformapi.services.AppReleaseService;
import com.coding.platformapi.services.AppServiceConfigService;
import com.coding.platformapi.models.ApplicationCreateRequest;
import com.coding.platformapi.models.ApplicationDeleteRequest;
import com.coding.platformapi.models.ApplicationOptionsView;
import com.coding.platformapi.models.ApplicationQueryRequest;
import com.coding.platformapi.models.ApplicationView;
import com.coding.common.security.AuthContext;
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

    @Autowired
    private AppExecutionLogService executionLogs;

    private final AppServiceConfigService serviceConfig;
    private final AppReleaseService release;
    private final AppDependencyService dependency;
    private final AppCanaryService canary;

    public ApplicationController(ApplicationService service, AppServiceConfigService serviceConfig,
                                 AppReleaseService release, AppDependencyService dependency,
                                 AppCanaryService canary) {
        this.service = service;
        this.serviceConfig = serviceConfig;
        this.release = release;
        this.dependency = dependency;
        this.canary = canary;
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

    @PostMapping("/settings/update")
    @Auditable(action = AuditAction.APPLICATION_SETTINGS_UPDATE, target = "#request.appId")
    @Operation(summary = "修改应用设置（链路追踪 / 服务网格；只写期望值，发布勾选「应用设置」后生效；version 乐观锁）")
    public ResponseData<AppSettingsView> settingsUpdate(@RequestBody AppSettingsRequest request) {
        return new ResponseData<>(serviceConfig.updateSettings(request, auth.effectiveTenantId(request.getTenantId())));
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

    @PostMapping("/release/offline")
    @Auditable(action = AuditAction.APPLICATION_RELEASE_OFFLINE, target = "#request.appId")
    @Operation(summary = "下线：删除应用在集群里的全部对象（持久化数据卷保留），记一条下线发布（异步执行，返回执行 id）")
    public ResponseData<Long> releaseOffline(@RequestBody AppReleaseRequest request) {
        return new ResponseData<>(release.offline(request.getAppId(), request.getNote(),
                operator(), auth.effectiveTenantId(request.getTenantId())));
    }

    // ------------------------------------------------------------------ 入口灰度

    @PostMapping("/canary/start")
    @Auditable(action = AuditAction.APPLICATION_CANARY_START, target = "#request.appId + '/' + #request.serviceKey")
    @Operation(summary = "开始入口灰度：线上快照为底，给该服务加灰度版本与加权路由（异步执行，返回执行 id）")
    public ResponseData<Long> canaryStart(@RequestBody AppCanaryRequest request) {
        return new ResponseData<>(canary.start(request, operator(), auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/canary/update")
    @Auditable(action = AuditAction.APPLICATION_CANARY_UPDATE, target = "#request.appId + '/' + #request.serviceKey")
    @Operation(summary = "调整灰度：权重 / header / 灰度版本配置，只改给出的字段（异步执行，返回执行 id）")
    public ResponseData<Long> canaryUpdate(@RequestBody AppCanaryRequest request) {
        return new ResponseData<>(canary.update(request, operator(), auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/canary/promote")
    @Auditable(action = AuditAction.APPLICATION_CANARY_PROMOTE, target = "#request.appId + '/' + #request.serviceKey")
    @Operation(summary = "灰度全量：灰度版本成为新的 stable，删除灰度版本（异步执行，返回执行 id）")
    public ResponseData<Long> canaryPromote(@RequestBody AppCanaryRequest request) {
        return new ResponseData<>(canary.promote(request.getAppId(), request.getServiceKey(), request.getNote(),
                operator(), auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/canary/abort")
    @Auditable(action = AuditAction.APPLICATION_CANARY_ABORT, target = "#request.appId + '/' + #request.serviceKey")
    @Operation(summary = "灰度回退：回到灰度开始前（stable 恢复为灰度前配置），删除灰度版本（异步执行，返回执行 id）")
    public ResponseData<Long> canaryAbort(@RequestBody AppCanaryRequest request) {
        return new ResponseData<>(canary.abort(request.getAppId(), request.getServiceKey(), request.getNote(),
                operator(), auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/canary/metrics")
    @Operation(summary = "灰度指标对比：stable / canary 两个版本的网关侧即时指标（window：5m / 15m / 1h）")
    public ResponseData<AppCanaryMetricsView> canaryMetrics(@RequestBody AppCanaryRequest request) {
        return new ResponseData<>(canary.metrics(request.getAppId(), request.getServiceKey(), request.getWindow(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/release/list")
    @Operation(summary = "发布记录（新到旧）", description = "带 page/size 走 PageHelper 分页，都不给 = 返回全量")
    public ResponseData<PageResult<AppReleaseView>> releaseList(@RequestBody AppReleaseListRequest request) {
        return new ResponseData<>(release.list(request.getAppId(), auth.effectiveTenantId(request.getTenantId()),
                request.getPage(), request.getSize()));
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

    @PostMapping("/execution/logs")
    @Operation(summary = "某次发布的过程日志（每阶段做了什么、健康检查卡在哪），按时间正序")
    public ResponseData<List<AppExecutionLogView>> executionLogs(@RequestBody AppReleaseRequest request) {
        return new ResponseData<>(executionLogs.list(request.getAppId(), request.getReleaseNo(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/execution/resume")
    @Auditable(action = AuditAction.APPLICATION_EXECUTION_RESUME, target = "#request.executionId")
    @Operation(summary = "继续失败或中断的执行（用当前登录身份接着跑）")
    public ResponseData<Void> executionResume(@RequestBody AppReleaseRequest request) {
        release.resume(request.getExecutionId(), operator(), auth.effectiveTenantId(request.getTenantId()));
        return new ResponseData<>();
    }

    @PostMapping("/execution/cancel")
    @Auditable(action = AuditAction.APPLICATION_RELEASE_CANCEL, target = "#request.appId")
    @Operation(summary = "取消正在进行的发布",
            description = "已下发到集群的对象不回滚；之后可以「继续」从当前阶段接着跑，或直接发新版本取代它")
    public ResponseData<Void> executionCancel(@RequestBody AppReleaseRequest request) {
        release.cancel(request.getAppId(), operator(), auth.effectiveTenantId(request.getTenantId()));
        return new ResponseData<>();
    }

    private String operator() {
        var me = auth.current();
        return me != null ? me.getUsername() : "unknown";
    }
}
