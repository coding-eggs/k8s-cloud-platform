package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.HttpRouteDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.services.HttpRouteService;
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
 * 资源管理 - HTTPRoute（gateway.networking.k8s.io/v1 CRD，租户域，集群未装 Gateway API 时透传 404）。
 * <p>本层只做 HTTP 绑定与 {@link ResponseData} 包装；业务规则在 {@link HttpRouteService}。
 * <p>rules/matches/filters/backendRefs 的未建模子字段保全在 k8s-core 的 fetch-overlay 里，
 * 与本层无关 —— 编辑一次不会丢 {@code timeouts} 或 {@code CORS} filter。
 */
@Tag(name = "资源管理-HTTPRoute", description = "命名空间内 HTTPRoute（租户域）")
@RestController
@RequestMapping("/httproutes")
@RequiredArgsConstructor
public class HttpRouteController {

    private final HttpRouteService httpRouteService;

    @PostMapping("/list")
    @Operation(summary = "列出 HTTPRoute")
    public ResponseData<List<HttpRouteDTO>> list(@RequestBody HttpRouteDTO body) {
        return new ResponseData<>(httpRouteService.list(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询 HTTPRoute")
    public ResponseData<HttpRouteDTO> get(@PathVariable String name,
                                          @RequestParam String tenantId,
                                          @RequestParam String clusterId,
                                          @RequestParam String namespace) {
        return new ResponseData<>(httpRouteService.get(name, tenantId, clusterId, namespace));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询 HTTPRoute YAML（只读全保真，未建模 filter/match 的逃生舱）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        return new ResponseData<>(httpRouteService.yaml(name, tenantId, clusterId, namespace));
    }

    @PostMapping
    @Operation(summary = "创建 HTTPRoute")
    public ResponseData<HttpRouteDTO> create(@RequestBody HttpRouteDTO body) {
        return new ResponseData<>(httpRouteService.create(body));
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新 HTTPRoute")
    public ResponseData<HttpRouteDTO> update(@PathVariable String name, @RequestBody HttpRouteDTO body) {
        body.setName(name);
        return new ResponseData<>(httpRouteService.update(body));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除 HTTPRoute")
    public ResponseData<Void> delete(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        httpRouteService.delete(name, tenantId, clusterId, namespace);
        return new ResponseData<>();
    }

}
