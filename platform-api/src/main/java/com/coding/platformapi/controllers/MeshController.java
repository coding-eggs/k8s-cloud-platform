package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.GatewayClassDTO;
import com.coding.common.models.k8s.dto.MeshStatusDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.services.MeshService;
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
 * <p>除 {@code /mesh/gatewayclass-refs}（豁免，纯候选值）外，每个端点都须有权限行
 * （{@code platform:cluster:manage}），否则 PermissionCrossCheckRunner 启动 brick（见 Flyway V2026_10_08_4）。
 * <p>命名空间级的 Gateway / HTTPRoute <b>不在这里</b> —— 它们是标准六操作 + 租户域，各自独立 controller。
 */
@Tag(name = "服务网格", description = "GatewayClass（集群级、平台管理）+ 网格状态探测")
@RestController
@RequestMapping("/mesh")
@RequiredArgsConstructor
public class MeshController {

    private final MeshService mesh;

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
    @Operation(summary = "命名空间内 waypoint Gateway 名列表",
            description = "供「命名空间编辑」的 istio.io/use-waypoint 下拉。平台限制每命名空间至多一个 waypoint，"
                    + "故常态 0 或 1 个。走本端点而非租户域 /gateways：命名空间是平台侧资源，平台管理员无租户上下文。")
    public ResponseData<List<String>> waypointCandidates(@RequestParam String clusterId, @RequestParam String namespace) {
        return new ResponseData<>(mesh.waypointCandidates(clusterId, namespace));
    }

}
