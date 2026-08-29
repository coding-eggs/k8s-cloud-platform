package com.coding.platformapi.controllers.resource;

import com.coding.common.models.k8s.dto.admin.ResourceContextDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.services.ResourceContextService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资源管理上下文级联（租户 → 集群 → 命名空间，顶栏 chip 用）。
 */
@Tag(name = "资源管理-上下文", description = "资源管理上下文级联数据")
@RestController
@RequestMapping("/resource")
@RequiredArgsConstructor
public class ResourceContextController {

    private final ResourceContextService contextService;

    @GetMapping("/context")
    @Operation(summary = "资源管理上下文", description = "租户 → 集群 → 命名空间级联数据（顶栏 chip 用）")
    public ResponseData<ResourceContextDTO> context() {
        return new ResponseData<>(contextService.build());
    }

}
