package com.coding.k8sserver.controllers.middleware;

import com.coding.common.models.middleware.MiddlewareOperatorInstallRequestDTO;
import com.coding.common.models.middleware.MiddlewareOperatorStatusDTO;
import com.coding.common.models.middleware.MiddlewareOperatorStatusRequestDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import com.coding.k8sserver.middleware.MiddlewareOperatorCatalog;
import com.coding.k8sserver.middleware.MiddlewareOperatorInstaller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** 中间件 Operator 的安装与状态（**PLATFORM 边界**：只有管理员路径）。 */
@Tag(name = "中间件-Operator", description = "Operator 安装/升级与状态判定")
@RestController
@RequestMapping("/middleware/operators")
public class MiddlewareOperatorController implements AccessBoundaryAware {

    private final MiddlewareOperatorInstaller installer;
    private final MiddlewareOperatorCatalog catalog;

    public MiddlewareOperatorController(MiddlewareOperatorInstaller installer,
                                        MiddlewareOperatorCatalog catalog) {
        this.installer = installer;
        this.catalog = catalog;
    }

    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.PLATFORM;
    }

    @PostMapping("/status")
    @Operation(summary = "批量判定 Operator 状态（codes 为空 = 全部）")
    public ResponseData<List<MiddlewareOperatorStatusDTO>> status(
            @RequestBody MiddlewareOperatorStatusRequestDTO request) {
        List<String> codes = request.getCodes() == null || request.getCodes().isEmpty()
                ? new ArrayList<>(catalog.codes())
                : request.getCodes();
        List<MiddlewareOperatorStatusDTO> out = new ArrayList<>();
        for (String code : codes) {
            out.add(installer.status(request.getClusterId(), code));
        }
        return new ResponseData<>(out);
    }

    @PostMapping("/install")
    @Operation(summary = "安装/升级 Operator（幂等，重复调用即 upgrade）")
    public ResponseData<MiddlewareOperatorStatusDTO> install(
            @RequestBody MiddlewareOperatorInstallRequestDTO request) {
        return new ResponseData<>(installer.install(request.getClusterId(), request.getCode()));
    }
}
