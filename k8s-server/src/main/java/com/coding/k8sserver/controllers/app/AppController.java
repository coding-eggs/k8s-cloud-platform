package com.coding.k8sserver.controllers.app;

import com.coding.common.models.app.AppApplyRequestDTO;
import com.coding.common.models.app.AppPruneRequestDTO;
import com.coding.common.models.app.AppRenderRequestDTO;
import com.coding.common.models.app.AppRenderResultDTO;
import com.coding.common.models.app.AppStatusRequestDTO;
import com.coding.common.models.app.AppStatusResultDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8sserver.app.AppApplyService;
import com.coding.k8sserver.app.AppRenderService;
import com.coding.k8sserver.app.AppStatusService;
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
@Tag(name = "应用编排", description = "渲染 / 落盘 / 清理 / 状态")
@RestController
@RequestMapping("/application")
public class AppController implements AccessBoundaryAware {

    private final AppRenderService render;
    private final AppApplyService apply;
    private final AppStatusService status;

    public AppController(AppRenderService render, AppApplyService apply, AppStatusService status) {
        this.render = render;
        this.apply = apply;
        this.status = status;
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

    @PostMapping("/apply")
    @Operation(summary = "把合并后的清单 SSA 到集群（fieldManager=platform-app）")
    public ResponseData<Void> apply(@RequestBody AppApplyRequestDTO request) {
        apply.apply(request);
        return new ResponseData<>();
    }

    @PostMapping("/prune")
    @Operation(summary = "删除差集对象（PVC 默认跳过）")
    public ResponseData<Integer> prune(@RequestBody AppPruneRequestDTO request) {
        return new ResponseData<>(apply.prune(request));
    }

    @PostMapping("/status")
    @Operation(summary = "现读这些对象的就绪状态")
    public ResponseData<AppStatusResultDTO> status(@RequestBody AppStatusRequestDTO request) {
        return new ResponseData<>(status.status(request));
    }
}
