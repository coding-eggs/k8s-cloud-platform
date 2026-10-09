package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.TcpRouteDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.services.TcpRouteService;
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
 * 资源管理 - TCPRoute（gateway.networking.k8s.io CRD，租户域，集群未装 Gateway API 时透传 404）。
 * <p>本层只做 HTTP 绑定与 {@link ResponseData} 包装；业务规则在 {@link TcpRouteService}。
 * <p>L4 路由没有七层匹配也没有 filter，建模核心集就是 parentRefs + rules{name, backendRefs}；
 * spec 级未建模键（如 v1.6 的 {@code useDefaultGateways}）的保全在 k8s-core 的 fetch-overlay 里，与本层无关。
 * <p><b>CRD 版本不固定</b>：v1.6 起 GA 于 {@code v1}，更早的集群只有 {@code v1alpha2} ——
 * 由后端按集群 capability 分派（{@code resolveL4Version}），前端与本层都不感知。
 */
@Tag(name = "资源管理-TCPRoute", description = "命名空间内 TCPRoute（租户域）")
@RestController
@RequestMapping("/tcproutes")
@RequiredArgsConstructor
public class TcpRouteController {

    private final TcpRouteService tcpRouteService;

    @PostMapping("/list")
    @Operation(summary = "列出 TCPRoute")
    public ResponseData<List<TcpRouteDTO>> list(@RequestBody TcpRouteDTO body) {
        return new ResponseData<>(tcpRouteService.list(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询 TCPRoute")
    public ResponseData<TcpRouteDTO> get(@PathVariable String name,
                                          @RequestParam String tenantId,
                                          @RequestParam String clusterId,
                                          @RequestParam String namespace) {
        return new ResponseData<>(tcpRouteService.get(name, tenantId, clusterId, namespace));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询 TCPRoute YAML（只读全保真）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        return new ResponseData<>(tcpRouteService.yaml(name, tenantId, clusterId, namespace));
    }

    @PostMapping
    @Operation(summary = "创建 TCPRoute")
    public ResponseData<TcpRouteDTO> create(@RequestBody TcpRouteDTO body) {
        return new ResponseData<>(tcpRouteService.create(body));
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新 TCPRoute")
    public ResponseData<TcpRouteDTO> update(@PathVariable String name, @RequestBody TcpRouteDTO body) {
        body.setName(name);
        return new ResponseData<>(tcpRouteService.update(body));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除 TCPRoute")
    public ResponseData<Void> delete(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        tcpRouteService.delete(name, tenantId, clusterId, namespace);
        return new ResponseData<>();
    }

}
