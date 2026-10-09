package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.GrpcRouteDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.services.GrpcRouteService;
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
 * 资源管理 - GRPCRoute（gateway.networking.k8s.io/v1 CRD，租户域，集群未装 Gateway API 时透传 404）。
 * <p>本层只做 HTTP 绑定与 {@link ResponseData} 包装；业务规则在 {@link GrpcRouteService}。
 * <p>rules/matches/filters/backendRefs 的未建模子字段保全在 k8s-core 的 fetch-overlay 里，
 * 与本层无关 —— 编辑一次不会丢 {@code timeouts} 或 {@code CORS} filter。
 */
@Tag(name = "资源管理-GRPCRoute", description = "命名空间内 GRPCRoute（租户域）")
@RestController
@RequestMapping("/grpcroutes")
@RequiredArgsConstructor
public class GrpcRouteController {

    private final GrpcRouteService grpcRouteService;

    @PostMapping("/list")
    @Operation(summary = "列出 GRPCRoute")
    public ResponseData<List<GrpcRouteDTO>> list(@RequestBody GrpcRouteDTO body) {
        return new ResponseData<>(grpcRouteService.list(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询 GRPCRoute")
    public ResponseData<GrpcRouteDTO> get(@PathVariable String name,
                                          @RequestParam String tenantId,
                                          @RequestParam String clusterId,
                                          @RequestParam String namespace) {
        return new ResponseData<>(grpcRouteService.get(name, tenantId, clusterId, namespace));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询 GRPCRoute YAML（只读全保真，未建模 filter/match 的逃生舱）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        return new ResponseData<>(grpcRouteService.yaml(name, tenantId, clusterId, namespace));
    }

    @PostMapping
    @Operation(summary = "创建 GRPCRoute")
    public ResponseData<GrpcRouteDTO> create(@RequestBody GrpcRouteDTO body) {
        return new ResponseData<>(grpcRouteService.create(body));
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新 GRPCRoute")
    public ResponseData<GrpcRouteDTO> update(@PathVariable String name, @RequestBody GrpcRouteDTO body) {
        body.setName(name);
        return new ResponseData<>(grpcRouteService.update(body));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除 GRPCRoute")
    public ResponseData<Void> delete(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        grpcRouteService.delete(name, tenantId, clusterId, namespace);
        return new ResponseData<>();
    }

}
