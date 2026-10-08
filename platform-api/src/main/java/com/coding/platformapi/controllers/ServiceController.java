package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.ServiceDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.services.ServiceService;
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
 * 资源管理 - Service。本层只做 HTTP 绑定与 {@link ResponseData} 包装；
 * create/update 的 selector 绑定解析与 k8s-server 调用在 {@link ServiceService}
 * （分层约定见 docs/development/backend-layering.md）。
 */
@Tag(name = "资源管理-Service", description = "命名空间内 Service")
@RestController
@RequestMapping("/services")
@RequiredArgsConstructor
public class ServiceController {

    private final ServiceService serviceService;

    @PostMapping("/list")
    @Operation(summary = "列出 Service")
    public ResponseData<List<ServiceDTO>> list(@RequestBody ServiceDTO body) {
        return new ResponseData<>(serviceService.list(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询 Service")
    public ResponseData<ServiceDTO> get(@PathVariable String name,
                                        @RequestParam String tenantId,
                                        @RequestParam String clusterId,
                                        @RequestParam String namespace) {
        return new ResponseData<>(serviceService.get(name, tenantId, clusterId, namespace));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询 Service YAML（只读展示）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        return new ResponseData<>(serviceService.yaml(name, tenantId, clusterId, namespace));
    }

    @PostMapping
    @Operation(summary = "创建 Service")
    public ResponseData<ServiceDTO> create(@RequestBody ServiceDTO body) {
        return new ResponseData<>(serviceService.create(body));
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新 Service")
    public ResponseData<ServiceDTO> update(@PathVariable String name, @RequestBody ServiceDTO body) {
        body.setName(name);
        return new ResponseData<>(serviceService.update(body));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除 Service")
    public ResponseData<Void> delete(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        serviceService.delete(name, tenantId, clusterId, namespace);
        return new ResponseData<>();
    }

}
