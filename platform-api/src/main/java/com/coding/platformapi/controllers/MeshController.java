package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.GatewayClassDTO;
import com.coding.common.models.k8s.dto.MeshStatusDTO;
import com.coding.common.models.k8s.dto.ParentGatewayRefDTO;
import com.coding.common.models.k8s.dto.WaypointRefDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.metrics.MetricsService;
import com.coding.platformapi.metrics.dto.MeshTopologyRequest;
import com.coding.platformapi.metrics.dto.MeshTopologyResponse;
import com.coding.platformapi.services.MeshService;
import com.coding.platformapi.models.WaypointTracingRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 服务网格（集群级，平台管理面）：GatewayClass CRUD + 网格状态 + 租户只读引用投影。
 * <p>面向前端的顶层前缀 {@code /mesh/**}；委托 {@link MeshService}，再经 K8sMeshClient 打 k8s-server
 * 的无前缀端点 {@code /gatewayclasses/**}、{@code /mesh/**}。
 * <p>除 {@code /mesh/gatewayclass-refs}（豁免，纯候选值）外，每个端点都须有权限行：平台管理面的是
 * {@code platform:cluster:manage}；{@code /mesh/parent-gateways} 是<b>租户可调</b>的窄投影，
 * 用 {@code tenant:gateway:list}（见 Flyway V2026_10_31_4）。缺行时 PermissionCrossCheckRunner 启动 brick。
 * <p>命名空间级的 Gateway / HTTPRoute <b>不在这里</b> —— 它们是标准六操作 + 租户域，各自独立 controller。
 */
@Tag(name = "服务网格", description = "GatewayClass（集群级、平台管理）+ 网格状态探测")
@RestController
@RequestMapping("/mesh")
@RequiredArgsConstructor
public class MeshController {

    private final MeshService mesh;
    private final MetricsService metricsService;

    // ==================== GatewayClass CRUD（平台管理面） ====================

    @PostMapping("/gatewayclasses/list")
    @Operation(summary = "列出 GatewayClass（body 传 clusterId/labelSelector）")
    public ResponseData<List<GatewayClassDTO>> listGatewayClasses(@RequestBody GatewayClassDTO body) {
        return new ResponseData<>(mesh.listGatewayClasses(body));
    }

    @GetMapping("/gatewayclasses/{name}")
    @Operation(summary = "查询 GatewayClass")
    public ResponseData<GatewayClassDTO> getGatewayClass(@PathVariable String name, @RequestParam String clusterId) {
        return new ResponseData<>(mesh.getGatewayClass(clusterId, name));
    }

    @GetMapping("/gatewayclasses/{name}/yaml")
    @Operation(summary = "查询 GatewayClass YAML（只读全保真）")
    public ResponseData<String> gatewayClassYaml(@PathVariable String name, @RequestParam String clusterId) {
        return new ResponseData<>(mesh.gatewayClassYaml(clusterId, name));
    }

    @PostMapping("/gatewayclasses")
    @Operation(summary = "创建 GatewayClass")
    public ResponseData<GatewayClassDTO> createGatewayClass(@RequestParam String clusterId,
                                                            @RequestBody GatewayClassDTO body) {
        body.setClusterId(clusterId);
        return new ResponseData<>(mesh.createGatewayClass(body));
    }

    @PutMapping("/gatewayclasses/{name}")
    @Operation(summary = "更新 GatewayClass")
    public ResponseData<GatewayClassDTO> updateGatewayClass(@PathVariable String name,
                                                            @RequestParam String clusterId,
                                                            @RequestBody GatewayClassDTO body) {
        body.setName(name);
        body.setClusterId(clusterId);
        return new ResponseData<>(mesh.updateGatewayClass(body));
    }

    @DeleteMapping("/gatewayclasses/{name}")
    @Operation(summary = "删除 GatewayClass（被 Gateway 引用时由控制器拒绝，平台不做前置守卫）")
    public ResponseData<Void> deleteGatewayClass(@PathVariable String name, @RequestParam String clusterId) {
        mesh.deleteGatewayClass(clusterId, name);
        return new ResponseData<>();
    }

    // ==================== 租户只读引用（豁免权限行，见 ExemptPaths） ====================

    @PostMapping("/gatewayclass-refs")
    @Operation(summary = "GatewayClass 引用候选（窄投影：name/controllerName/description；供 Gateway 编辑器选 gatewayClassName）")
    public ResponseData<List<GatewayClassDTO>> gatewayClassRefs(@RequestParam String clusterId) {
        return new ResponseData<>(mesh.gatewayClassRefs(clusterId));
    }

    // ==================== 网格状态 ====================

    @PostMapping("/status")
    @Operation(summary = "服务网格状态（hasIstio / istioAmbient / hasGatewayApi / gatewayApiVersions）")
    public ResponseData<MeshStatusDTO> status(@RequestParam String clusterId) {
        return new ResponseData<>(mesh.meshStatus(clusterId));
    }

    // ==================== waypoint 候选（供命名空间编辑器） ====================

    @PostMapping("/gateways")
    @Operation(summary = "命名空间内 waypoint 引用候选（名字 + 处理哪类流量）",
            description = "供「命名空间编辑」的 istio.io/use-waypoint 下拉。数量不设上限；必须带 waypoint-for —— "
                    + "命名空间级只能指向能处理服务流量的（service / all），否则 istio 静默放行、L7 策略不生效。"
                    + "走本端点而非租户域 /gateways：命名空间是平台侧资源，平台管理员无租户上下文。")
    public ResponseData<List<WaypointRefDTO>> waypointCandidates(@RequestParam String clusterId, @RequestParam String namespace) {
        return new ResponseData<>(mesh.waypointCandidates(clusterId, namespace));
    }

    /**
     * 路由父引用候选（spec §5.3）。
     *
     * <p>⚠️ 这个端点<b>租户可调</b>，权限码是 {@code tenant:gateway:list}（不是上面那些 {@code platform:cluster:manage}）——
     * 它存在的理由就是"租户要把路由挂到平台代管的对外网关上，而租户对那个 ns 没有任何 K8s 权限"。
     * 白名单与字段投影都在 k8s-server 写死。
     */
    @PostMapping("/parent-gateways")
    @Operation(summary = "路由父引用候选（自己命名空间 + 平台网关白名单，窄投影）",
            description = "供 HTTPRoute / TCPRoute 等编辑器的 parentRefs 下拉。只回 name/namespace/listenerNames/waypointFor。")
    public ResponseData<List<ParentGatewayRefDTO>> parentGateways(@RequestParam(required = false) String tenantId,
                                                                 @RequestParam String clusterId,
                                                                 @RequestParam String namespace,
                                                                 @RequestParam(required = false) String targetNamespace) {
        return new ResponseData<>(mesh.parentGateways(tenantId, clusterId, namespace, targetNamespace));
    }

    // ==================== waypoint 链路追踪（批次二） ====================

    @PostMapping("/waypoints/tracing")
    @Operation(summary = "开/关 waypoint 的链路追踪",
            description = "下发/删除指向该 Gateway 的 Istio Telemetry；采样率取 OTel 组件上次成功快照（缺省 10）。"
                    + "Telemetry CRD 或 Gateway 不存在时返回 false（跳过）。")
    public ResponseData<Boolean> setWaypointTracing(@RequestBody WaypointTracingRequest body) {
        return new ResponseData<>(mesh.setWaypointTracing(body));
    }

    @GetMapping("/waypoints/tracing")
    @Operation(summary = "查询 waypoint 的链路追踪状态（编辑页回填）")
    public ResponseData<Boolean> waypointTracing(@RequestParam String tenantId,
                                                 @RequestParam String clusterId,
                                                 @RequestParam String namespace,
                                                 @RequestParam String gatewayName) {
        WaypointTracingRequest req = new WaypointTracingRequest();
        req.setTenantId(tenantId);
        req.setClusterId(clusterId);
        req.setNamespace(namespace);
        req.setGatewayName(gatewayName);
        return new ResponseData<>(mesh.waypointTracing(req));
    }

    // ==================== 网格拓扑（S2.4，只读） ====================

    @PostMapping("/topology")
    @Operation(summary = "服务网格拓扑",
            description = "返回节点与边（QPS / 错误率 / P95）。"
                    + "⚠️ meshMetricsAvailable=false 表示这个集群**没在采集**网格指标（与有没有流量无关）—— "
                    + "两种情况的边都是空的，前端必须给不同的文案。"
                    + "节点 id 是 `命名空间/工作负载`。")
    public ResponseData<MeshTopologyResponse> topology(@RequestBody MeshTopologyRequest request) {
        return new ResponseData<>(metricsService.meshTopology(
                request.getClusterId(), request.getNamespace(),
                request.getWindowSeconds() == null ? 300 : request.getWindowSeconds()));
    }

}
