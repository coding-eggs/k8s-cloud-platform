package com.coding.k8sserver.controllers.middleware;

import com.coding.common.models.middleware.MiddlewareOperatorInstallRequestDTO;
import com.coding.common.models.middleware.MiddlewareOperatorStatusDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import com.coding.k8sserver.middleware.MiddlewareOperatorInstaller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator 安装/升级（**PLATFORM 边界**：只有管理员路径）。 */
@Tag(name = "中间件-Operator", description = "安装/升级")
@RestController
@RequestMapping("/middleware/operators")
public class MiddlewareOperatorInstallController implements AccessBoundaryAware {

    private final MiddlewareOperatorInstaller installer;

    public MiddlewareOperatorInstallController(MiddlewareOperatorInstaller installer) {
        this.installer = installer;
    }

    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.PLATFORM;
    }

    @PostMapping("/install")
    @Operation(summary = "安装/升级 Operator（幂等，重复调用即 upgrade）")
    public ResponseData<MiddlewareOperatorStatusDTO> install(
            @RequestBody MiddlewareOperatorInstallRequestDTO request) {
        return new ResponseData<>(installer.install(request.getClusterId(), request.getCode(),
                request.getImageRegistryHost(), request.getImageRegistryProject()));
    }
}
