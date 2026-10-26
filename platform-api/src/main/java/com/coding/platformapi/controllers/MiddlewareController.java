package com.coding.platformapi.controllers;

import com.coding.common.models.middleware.MiddlewareCatalogItemDTO;
import com.coding.common.models.middleware.MiddlewareConnectionDTO;
import com.coding.common.models.middleware.MiddlewareFormOptionsDTO;
import com.coding.common.models.middleware.MiddlewareOperatorStatusDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.data.models.system.PlatformMiddlewareInstance;
import com.coding.data.models.system.PlatformMiddlewareOperation;
import com.coding.platformapi.audit.AuditAction;
import com.coding.platformapi.audit.Auditable;
import com.coding.platformapi.models.MiddlewareCreateRequest;
import com.coding.platformapi.models.MiddlewareDeleteRequest;
import com.coding.platformapi.models.MiddlewareOperatorInstallRequest;
import com.coding.platformapi.models.MiddlewareInstanceDetail;
import com.coding.platformapi.models.MiddlewareMetricsRequest;
import com.coding.platformapi.models.MiddlewareOptionsRequest;
import com.coding.platformapi.models.MiddlewarePasswordChangeRequest;
import com.coding.common.models.middleware.MiddlewareUserCreateRequestDTO;
import com.coding.common.models.middleware.MiddlewareUserCredentialDTO;
import com.coding.common.models.middleware.MiddlewareUserDeleteRequestDTO;
import com.coding.common.models.middleware.MiddlewareUserListRequestDTO;
import com.coding.common.models.middleware.MiddlewareUserPasswordRequestDTO;
import com.coding.common.models.middleware.MiddlewareUserView;
import com.coding.platformapi.services.MiddlewareUserService;
import com.coding.platformapi.models.MiddlewareRenameRequest;
import com.coding.platformapi.models.MiddlewareQueryRequest;
import com.coding.platformapi.models.MiddlewareScaleRequest;
import com.coding.platformapi.security.AuthContext;
import com.coding.platformapi.services.MiddlewareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 中间件商店（固定清单 + Operator 实例）。一页一 controller。 */
@Tag(name = "中间件商店", description = "清单 / 实例 / Operator")
@RestController
@RequestMapping("/middleware")
public class MiddlewareController {

    private final MiddlewareService service;
    private final MiddlewareUserService users;

    @Autowired
    private AuthContext auth;

    public MiddlewareController(MiddlewareService service, MiddlewareUserService users) {
        this.service = service;
        this.users = users;
    }

    @PostMapping("/catalog")
    @Operation(summary = "商店清单（含本集群 Operator 状态与实例数）")
    public ResponseData<List<MiddlewareCatalogItemDTO>> catalog(@RequestBody MiddlewareQueryRequest request) {
        return new ResponseData<>(service.catalog(request.getClusterId(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/form-options")
    @Operation(summary = "创建表单可选项（本租户命名空间 / 集群 StorageClass）")
    public ResponseData<MiddlewareFormOptionsDTO> formOptions(@RequestBody MiddlewareOptionsRequest request) {
        return new ResponseData<>(service.formOptions(request.getClusterId(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/instance/list")
    @Operation(summary = "实例列表（租户按归属收窄）")
    public ResponseData<List<PlatformMiddlewareInstance>> list(@RequestBody MiddlewareQueryRequest request) {
        return new ResponseData<>(service.list(request.getClusterId(), request.getCode(),
                auth.effectiveTenantId(request.getTenantId()), request.getNamespace()));
    }

    @PostMapping("/instance/get")
    @Operation(summary = "实例详情")
    public ResponseData<PlatformMiddlewareInstance> get(@RequestBody MiddlewareQueryRequest request) {
        return new ResponseData<>(service.get(request.getInstanceId(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/instance/create")
    @Auditable(action = AuditAction.MIDDLEWARE_CREATE, target = "#request.code + '/' + #request.namespace")
    @Operation(summary = "创建实例（异步操作记录；返回操作 id）")
    public ResponseData<Long> create(@RequestBody MiddlewareCreateRequest request) {
        return new ResponseData<>(service.create(request, operator(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/instance/connection")
    @Operation(summary = "实例连接方式（集群内地址 + 凭据位置）")
    public ResponseData<MiddlewareConnectionDTO> connection(@RequestBody MiddlewareQueryRequest request) {
        return new ResponseData<>(service.connection(request.getInstanceId(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/instance/detail")
    @Operation(summary = "实例详情（台账 + 规格 + 连接方式 + 实时对象）")
    public ResponseData<MiddlewareInstanceDetail> detail(@RequestBody MiddlewareQueryRequest request) {
        return new ResponseData<>(service.detail(request.getInstanceId(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/instance/operations")
    @Operation(summary = "实例的操作记录")
    public ResponseData<List<PlatformMiddlewareOperation>> operations(@RequestBody MiddlewareQueryRequest request) {
        return new ResponseData<>(service.operations(request.getInstanceId(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/instance/rename")
    @Auditable(action = AuditAction.MIDDLEWARE_RENAME, target = "#request.instanceId")
    @Operation(summary = "修改实例显示名（只改台账）")
    public ResponseData<PlatformMiddlewareInstance> rename(@RequestBody MiddlewareRenameRequest request) {
        return new ResponseData<>(service.rename(request.getInstanceId(), request.getDisplayName(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/instance/users")
    @Operation(summary = "实例的账号列表（内置账号 + 平台建的账号）")
    public ResponseData<List<MiddlewareUserView>> listUsers(@RequestBody MiddlewareUserListRequestDTO request) {
        return new ResponseData<>(users.list(request, auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/instance/users/roles")
    @Operation(summary = "该实例可选的账号角色")
    public ResponseData<List<String>> userRoles(@RequestBody MiddlewareUserListRequestDTO request) {
        return new ResponseData<>(users.roles(request.getInstanceId(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/instance/users/create")
    @Auditable(action = AuditAction.MIDDLEWARE_USER_CREATE, target = "#request.instanceId")
    @Operation(summary = "新增实例账号（返回一次性明文口令）")
    public ResponseData<MiddlewareUserCredentialDTO> createUser(
            @RequestBody MiddlewareUserCreateRequestDTO request) {
        return new ResponseData<>(users.create(request, auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/instance/users/password")
    @Auditable(action = AuditAction.MIDDLEWARE_USER_PASSWORD, target = "#request.instanceId")
    @Operation(summary = "改实例账号口令（返回一次性明文口令）")
    public ResponseData<MiddlewareUserCredentialDTO> changeUserPassword(
            @RequestBody MiddlewareUserPasswordRequestDTO request) {
        return new ResponseData<>(users.changePassword(request,
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/instance/users/delete")
    @Auditable(action = AuditAction.MIDDLEWARE_USER_DELETE, target = "#request.instanceId")
    @Operation(summary = "删除实例账号（内置账号拒绝）")
    public ResponseData<Boolean> deleteUser(@RequestBody MiddlewareUserDeleteRequestDTO request) {
        users.drop(request, auth.effectiveTenantId(request.getTenantId()));
        return new ResponseData<>(true);
    }

    @PostMapping("/instance/password")
    @Auditable(action = AuditAction.MIDDLEWARE_PASSWORD, target = "#request.instanceId")
    @Operation(summary = "改实例的鉴权口令（Milvus）",
            description = "旧口令由 k8s-server 从 Secret 读；新口令留空则平台生成。口令不回显，去 Secret 取")
    public ResponseData<Boolean> changePassword(@RequestBody MiddlewarePasswordChangeRequest request) {
        service.changePassword(request, auth.effectiveTenantId(request.getTenantId()));
        return new ResponseData<>(true);
    }

    @PostMapping("/instance/metrics")
    @Operation(summary = "实例监控曲线（CPU/内存/网络/磁盘，跨该实例全部 Pod 聚合）")
    public ResponseData<com.coding.platformapi.metrics.dto.MiddlewareMetricsResponse> metrics(
            @RequestBody MiddlewareMetricsRequest request) {
        request.setTenantId(auth.effectiveTenantId(request.getTenantId()));
        return new ResponseData<>(service.metrics(request));
    }

    @PostMapping("/instance/scale")
    @Auditable(action = AuditAction.MIDDLEWARE_SCALE, target = "#request.instanceId")
    @Operation(summary = "扩容实例（节点数/资源）")
    public ResponseData<Long> scale(@RequestBody MiddlewareScaleRequest request) {
        return new ResponseData<>(service.scale(request, operator(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/instance/delete")
    @Auditable(action = AuditAction.MIDDLEWARE_DELETE, target = "#request.instanceId")
    @Operation(summary = "删除实例（keepPvc=true 时保留数据卷）")
    public ResponseData<Long> delete(@RequestBody MiddlewareDeleteRequest request) {
        return new ResponseData<>(service.delete(request, operator(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/operation/get")
    @Operation(summary = "查询一条实例操作（轮询）")
    public ResponseData<PlatformMiddlewareOperation> operation(@RequestBody MiddlewareQueryRequest request) {
        return new ResponseData<>(service.operation(request.getOperationId(),
                auth.effectiveTenantId(request.getTenantId())));
    }

    @PostMapping("/operator/install")
    @Auditable(action = AuditAction.MIDDLEWARE_OPERATOR_INSTALL, target = "#request.code")
    @Operation(summary = "安装/升级 Operator（管理员）")
    public ResponseData<MiddlewareOperatorStatusDTO> installOperator(
            @RequestBody MiddlewareOperatorInstallRequest request) {
        return new ResponseData<>(service.installOperator(request.getClusterId(), request.getCode()));
    }

    private String operator() {
        var me = auth.current();
        return me != null ? me.getUsername() : "unknown";
    }
}
