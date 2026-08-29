package com.coding.platformapi.controllers.resource;

import com.coding.common.models.k8s.dto.ConfigMapDTO;
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
 * 资源管理 - ConfigMap（全链路参考实现，其余资源同模式扩展）。
 * <p>
 * 本层只做方法级透传：platform-api → k8s-server /resources/configmaps（K8sResourceClient DTO 驱动）。
 * list/create/update 一律 body DTO 进（原样透传）；get/yaml/delete 按名寻址，身份参数走 query（显式命名）。
 * 身份解析与命名空间边界校验在 k8s-server 侧执行，platform-api 不重复实现。
 */
@Tag(name = "资源管理-ConfigMap", description = "命名空间内 ConfigMap")
@RestController
@RequestMapping("/resource/configmaps")
@RequiredArgsConstructor
public class ConfigMapController {

    private final K8sResourceClient k8s;

    @PostMapping("/list")
    @Operation(summary = "列出 ConfigMap")
    public ResponseData<List<ConfigMapDTO>> list(@RequestBody ConfigMapDTO body) {
        //body 携带 tenantId/clusterId/namespace/labelSelector，与 k8s-server wire 同构，原样透传
        return new ResponseData<>(k8s.list(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询 ConfigMap")
    public ResponseData<ConfigMapDTO> get(@PathVariable String name,
                                          @RequestParam String tenantId,
                                          @RequestParam String clusterId,
                                          @RequestParam String namespace) {
        return new ResponseData<>(k8s.get(dto(name, tenantId, clusterId, namespace)));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询 ConfigMap YAML（只读展示）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        return new ResponseData<>(k8s.yaml(dto(name, tenantId, clusterId, namespace)));
    }

    @PostMapping
    @Operation(summary = "创建 ConfigMap")
    public ResponseData<ConfigMapDTO> create(@RequestBody ConfigMapDTO body) {
        //body 携带 tenantId/clusterId/namespace；K8sResourceClient 将前两者提取进 query（k8s-server 以 query 为准）
        return new ResponseData<>(k8s.create(body));
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新 ConfigMap")
    public ResponseData<ConfigMapDTO> update(@PathVariable String name, @RequestBody ConfigMapDTO body) {
        body.setName(name);
        return new ResponseData<>(k8s.update(body));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除 ConfigMap")
    public ResponseData<Void> delete(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        k8s.delete(dto(name, tenantId, clusterId, namespace));
        return new ResponseData<>();
    }

    /**查询 DTO：apiPath 内置于 DTO */
    private ConfigMapDTO dto(String name, String tenantId, String clusterId, String namespace) {
        ConfigMapDTO d = new ConfigMapDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        d.setNamespace(namespace);
        return d;
    }

}
