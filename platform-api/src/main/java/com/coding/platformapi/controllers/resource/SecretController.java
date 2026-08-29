package com.coding.platformapi.controllers.resource;

import com.coding.common.models.k8s.dto.SecretDTO;
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
 * 资源管理 - Secret。透传约定同 ConfigMapController（k8s-server /resources/secrets）。
 */
@Tag(name = "资源管理-Secret", description = "命名空间内 Secret")
@RestController
@RequestMapping("/resource/secrets")
@RequiredArgsConstructor
public class SecretController {

    private final K8sResourceClient k8s;

    @PostMapping("/list")
    @Operation(summary = "列出 Secret")
    public ResponseData<List<SecretDTO>> list(@RequestBody SecretDTO body) {
        return new ResponseData<>(k8s.list(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询 Secret")
    public ResponseData<SecretDTO> get(@PathVariable String name,
                                       @RequestParam String tenantId,
                                       @RequestParam String clusterId,
                                       @RequestParam String namespace) {
        return new ResponseData<>(k8s.get(dto(name, tenantId, clusterId, namespace)));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询 Secret YAML（只读展示）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        return new ResponseData<>(k8s.yaml(dto(name, tenantId, clusterId, namespace)));
    }

    @PostMapping
    @Operation(summary = "创建 Secret")
    public ResponseData<SecretDTO> create(@RequestBody SecretDTO body) {
        //body 携带 tenantId/clusterId/namespace；K8sResourceClient 将前两者提取进 query（k8s-server 以 query 为准）
        return new ResponseData<>(k8s.create(body));
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新 Secret")
    public ResponseData<SecretDTO> update(@PathVariable String name, @RequestBody SecretDTO body) {
        body.setName(name);
        return new ResponseData<>(k8s.update(body));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除 Secret")
    public ResponseData<Void> delete(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        k8s.delete(dto(name, tenantId, clusterId, namespace));
        return new ResponseData<>();
    }

    /**查询 DTO：apiPath 内置于 DTO */
    private SecretDTO dto(String name, String tenantId, String clusterId, String namespace) {
        SecretDTO d = new SecretDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        d.setNamespace(namespace);
        return d;
    }

}
