package com.coding.platformapi.controllers.resource;

import com.coding.common.models.k8s.dto.ServiceDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.k8s.K8sResourceClient;
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
 * 资源管理 - Service。透传约定同 ConfigMapController（k8s-server /resources/services）。
 */
@Tag(name = "资源管理-Service", description = "命名空间内 Service")
@RestController
@RequestMapping("/resource/services")
@RequiredArgsConstructor
public class ServiceController {

    private final K8sResourceClient k8s;

    @PostMapping("/list")
    @Operation(summary = "列出 Service")
    public ResponseData<List<ServiceDTO>> list(@RequestBody ServiceDTO body) {
        return new ResponseData<>(k8s.list(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询 Service")
    public ResponseData<ServiceDTO> get(@PathVariable String name,
                                        @RequestParam String tenantId,
                                        @RequestParam String clusterId,
                                        @RequestParam String namespace) {
        return new ResponseData<>(k8s.get(dto(name, tenantId, clusterId, namespace)));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询 Service YAML（只读展示）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        return new ResponseData<>(k8s.yaml(dto(name, tenantId, clusterId, namespace)));
    }

    @PostMapping
    @Operation(summary = "创建 Service")
    public ResponseData<ServiceDTO> create(@RequestBody ServiceDTO body) {
        //body 携带 tenantId/clusterId/namespace；K8sResourceClient 将前两者提取进 query（k8s-server 以 query 为准）
        return new ResponseData<>(k8s.create(body));
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新 Service")
    public ResponseData<ServiceDTO> update(@PathVariable String name, @RequestBody ServiceDTO body) {
        body.setName(name);
        return new ResponseData<>(k8s.update(body));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除 Service")
    public ResponseData<Void> delete(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        k8s.delete(dto(name, tenantId, clusterId, namespace));
        return new ResponseData<>();
    }

    /**查询 DTO：apiPath 内置于 DTO */
    private ServiceDTO dto(String name, String tenantId, String clusterId, String namespace) {
        ServiceDTO d = new ServiceDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        d.setNamespace(namespace);
        return d;
    }

}
