package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.GatewayDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.services.GatewayService;
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
 * 资源管理 - Gateway（gateway.networking.k8s.io/v1 CRD，租户域，集群未装 Gateway API 时透传 404）。
 * <p>本层只做 HTTP 绑定与 {@link ResponseData} 包装；业务规则在 {@link GatewayService}
 * （分层约定见 docs/development/backend-layering.md）。
 * <p>路径 {@code /gateways} 与 k8s-server 侧同名（三族前缀已去掉），也须与
 * {@code GatewayDTO#getApiPath()} 一致 —— k8s-server 侧 {@code AbstractNamespacedResourceController}
 * 按分配表三元组（tenantId, clusterId, namespace）判边界。
 */
@Tag(name = "资源管理-Gateway", description = "命名空间内 Gateway（租户域）")
@RestController
@RequestMapping("/gateways")
@RequiredArgsConstructor
public class GatewayController {

    private final GatewayService gatewayService;

    @PostMapping("/list")
    @Operation(summary = "列出 Gateway")
    public ResponseData<List<GatewayDTO>> list(@RequestBody GatewayDTO body) {
        return new ResponseData<>(gatewayService.list(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询 Gateway")
    public ResponseData<GatewayDTO> get(@PathVariable String name,
                                        @RequestParam String tenantId,
                                        @RequestParam String clusterId,
                                        @RequestParam String namespace) {
        return new ResponseData<>(gatewayService.get(name, tenantId, clusterId, namespace));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询 Gateway YAML（只读全保真）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        return new ResponseData<>(gatewayService.yaml(name, tenantId, clusterId, namespace));
    }

    @PostMapping
    @Operation(summary = "创建 Gateway")
    public ResponseData<GatewayDTO> create(@RequestBody GatewayDTO body) {
        return new ResponseData<>(gatewayService.create(body));
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新 Gateway")
    public ResponseData<GatewayDTO> update(@PathVariable String name, @RequestBody GatewayDTO body) {
        body.setName(name);
        return new ResponseData<>(gatewayService.update(body));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除 Gateway")
    public ResponseData<Void> delete(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        gatewayService.delete(name, tenantId, clusterId, namespace);
        return new ResponseData<>();
    }

}
