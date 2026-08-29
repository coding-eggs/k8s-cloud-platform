package com.coding.platformapi.controllers.resource;

import com.coding.common.models.k8s.dto.ServiceMonitorDTO;
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
 * 资源管理 - ServiceMonitor（CRD，集群未装 Prometheus Operator 时透传 404）。透传约定同 ConfigMapController。
 */
@Tag(name = "资源管理-ServiceMonitor", description = "命名空间内 ServiceMonitor")
@RestController
@RequestMapping("/resource/servicemonitors")
@RequiredArgsConstructor
public class ServiceMonitorController {

    private final K8sResourceClient k8s;

    @PostMapping("/list")
    @Operation(summary = "列出 ServiceMonitor")
    public ResponseData<List<ServiceMonitorDTO>> list(@RequestBody ServiceMonitorDTO body) {
        return new ResponseData<>(k8s.list(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询 ServiceMonitor")
    public ResponseData<ServiceMonitorDTO> get(@PathVariable String name,
                                               @RequestParam String tenantId,
                                               @RequestParam String clusterId,
                                               @RequestParam String namespace) {
        return new ResponseData<>(k8s.get(dto(name, tenantId, clusterId, namespace)));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询 ServiceMonitor YAML（只读展示）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        return new ResponseData<>(k8s.yaml(dto(name, tenantId, clusterId, namespace)));
    }

    @PostMapping
    @Operation(summary = "创建 ServiceMonitor")
    public ResponseData<ServiceMonitorDTO> create(@RequestBody ServiceMonitorDTO body) {
        //body 携带 tenantId/clusterId/namespace；K8sResourceClient 将前两者提取进 query（k8s-server 以 query 为准）
        return new ResponseData<>(k8s.create(body));
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新 ServiceMonitor")
    public ResponseData<ServiceMonitorDTO> update(@PathVariable String name, @RequestBody ServiceMonitorDTO body) {
        body.setName(name);
        return new ResponseData<>(k8s.update(body));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除 ServiceMonitor")
    public ResponseData<Void> delete(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        k8s.delete(dto(name, tenantId, clusterId, namespace));
        return new ResponseData<>();
    }

    /**查询 DTO：apiPath 内置于 DTO */
    private ServiceMonitorDTO dto(String name, String tenantId, String clusterId, String namespace) {
        ServiceMonitorDTO d = new ServiceMonitorDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        d.setNamespace(namespace);
        return d;
    }

}
