package com.coding.k8sserver.controllers.app;

import com.coding.common.models.app.AppApplyRequestDTO;
import com.coding.common.models.app.AppInitRequestDTO;
import com.coding.common.models.app.AppInitResultDTO;
import com.coding.common.models.app.AppPruneRequestDTO;
import com.coding.common.models.app.AppRenderRequestDTO;
import com.coding.common.models.app.AppRenderResultDTO;
import com.coding.common.models.app.AppStatusRequestDTO;
import com.coding.common.models.app.AppStatusResultDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.common.models.k8s.dto.IngressListenerSetRequestDTO;
import com.coding.common.models.k8s.dto.IngressListenerSetStatusDTO;
import com.coding.k8sserver.app.AppApplyService;
import com.coding.k8sserver.app.AppIngressService;
import com.coding.k8sserver.app.AppInitService;
import com.coding.k8sserver.app.AppRenderService;
import com.coding.k8sserver.app.AppStatusService;
import com.coding.k8sserver.components.AccessBoundary;
import com.coding.k8sserver.components.AccessBoundaryAware;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final AppInitService init;
    private final AppIngressService ingress;

    public AppController(AppRenderService render, AppApplyService apply, AppStatusService status,
                         AppInitService init, AppIngressService ingress) {
        this.render = render;
        this.apply = apply;
        this.status = status;
        this.init = init;
        this.ingress = ingress;
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

    @PostMapping("/init")
    @Operation(summary = "跑一次中间件初始化（一次性 Job，等完成并取日志）")
    public ResponseData<AppInitResultDTO> init(@RequestBody AppInitRequestDTO request) {
        return new ResponseData<>(init.run(request));
    }

    @PostMapping("/status")
    @Operation(summary = "现读这些对象的就绪状态")
    public ResponseData<AppStatusResultDTO> status(@RequestBody AppStatusRequestDTO request) {
        return new ResponseData<>(status.status(request));
    }

    @PostMapping("/ingress-listenerset")
    @Operation(summary = "下发/删除命名空间的应用端口池 ListenerSet",
            description = "幂等 SSA；listeners 为空 = 删除并去掉命名空间放行标签；ListenerSet CRD 缺失返回 false")
    public ResponseData<Boolean> ingressListenerSet(@RequestBody IngressListenerSetRequestDTO request) {
        return new ResponseData<>(ingress.apply(request));
    }

    @GetMapping("/ingress-listenerset")
    @Operation(summary = "查询命名空间应用端口池 ListenerSet 状态")
    public ResponseData<IngressListenerSetStatusDTO> ingressListenerSetStatus(@RequestParam String clusterId,
                                                                             @RequestParam String namespace,
                                                                             @RequestParam String name) {
        return new ResponseData<>(ingress.status(clusterId, namespace, name));
    }

    @GetMapping("/service-ports")
    @Operation(summary = "查询 Service 声明的端口（应用端口池只分配网关 Service 上已声明的端口）")
    public ResponseData<java.util.List<Integer>> servicePorts(@RequestParam String clusterId,
                                                              @RequestParam String namespace,
                                                              @RequestParam String name) {
        return new ResponseData<>(ingress.servicePorts(clusterId, namespace, name));
    }
}
