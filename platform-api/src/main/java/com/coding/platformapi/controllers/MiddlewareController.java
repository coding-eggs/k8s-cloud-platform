package com.coding.platformapi.controllers;

import com.coding.common.models.middleware.MiddlewareCatalogItemDTO;
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
import com.coding.platformapi.models.MiddlewareOptionsRequest;
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

    @Autowired
    private AuthContext auth;

    public MiddlewareController(MiddlewareService service) {
        this.service = service;
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
                auth.effectiveTenantId(request.getTenantId())));
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
