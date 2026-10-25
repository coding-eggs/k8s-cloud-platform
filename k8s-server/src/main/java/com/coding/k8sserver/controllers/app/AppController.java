package com.coding.k8sserver.controllers.app;

import com.coding.common.models.app.AppRenderRequestDTO;
import com.coding.common.models.app.AppRenderResultDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8sserver.app.AppRenderService;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用编排的集群侧入口（**UPSTREAM 边界**）：只由 platform-api 调用，归属校验与权限在那一侧。
 */
@Tag(name = "应用编排-渲染", description = "渲染 / 落盘 / 清理 / 状态")
@RestController
@RequestMapping("/application")
public class AppController implements AccessBoundaryAware {

    private final AppRenderService render;

    public AppController(AppRenderService render) {
        this.render = render;
    }

    @Override
    public AccessBoundary accessBoundary() {
        return AccessBoundary.UPSTREAM;
    }

    @PostMapping("/render")
    @Operation(summary = "逐节点渲染并合并成一份清单（不写集群）")
    public ResponseData<AppRenderResultDTO> render(@RequestBody AppRenderRequestDTO request) {
        return new ResponseData<>(render.render(request));
    }
}
