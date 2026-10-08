package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.ConfigMapDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.services.ConfigMapService;
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
 * 本层只做 HTTP 绑定与 {@link ResponseData} 包装：DTO 组装、业务规则、k8s-server 调用全在
 * {@link ConfigMapService}（分层约定见 docs/development/backend-layering.md）。
 * list/create/update 一律 body DTO 进；get/yaml/delete 按名寻址，身份参数走 query（显式命名）。
 * 身份解析与命名空间边界校验在 k8s-server 侧执行，platform-api 不重复实现。
 */
@Tag(name = "资源管理-ConfigMap", description = "命名空间内 ConfigMap")
@RestController
@RequestMapping("/configmaps")
@RequiredArgsConstructor
public class ConfigMapController {

    private final ConfigMapService configMapService;

    @PostMapping("/list")
    @Operation(summary = "列出 ConfigMap")
    public ResponseData<List<ConfigMapDTO>> list(@RequestBody ConfigMapDTO body) {
        //body 携带 tenantId/clusterId/namespace/labelSelector，与 k8s-server wire 同构，原样透传
        return new ResponseData<>(configMapService.list(body));
    }

    /**
     * 跨全部命名空间列举（平台侧）。
     * <p>
     * <b>为什么必须是独立路径</b>：权限表按 {@code (方法, 路径)} 定码，同一路径同一方法只能是一组 code ——
     * 想给"全命名空间列举"单独授权，就必须是另一个路径。它的码是 {@code platform:configmap:list-all}
     * （租户的 list 是 {@code tenant:configmap:list}），互不影响。
     * <p>body 不带 namespace；返回项各自带自己的 namespace。
     */
    @PostMapping("/list-all")
    @Operation(summary = "跨全部命名空间列出 ConfigMap（平台侧）")
    public ResponseData<List<ConfigMapDTO>> listAll(@RequestBody ConfigMapDTO body) {
        return new ResponseData<>(configMapService.listAll(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询 ConfigMap")
    public ResponseData<ConfigMapDTO> get(@PathVariable String name,
                                          @RequestParam String tenantId,
                                          @RequestParam String clusterId,
                                          @RequestParam String namespace) {
        return new ResponseData<>(configMapService.get(name, tenantId, clusterId, namespace));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询 ConfigMap YAML（只读展示）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        return new ResponseData<>(configMapService.yaml(name, tenantId, clusterId, namespace));
    }

    @PostMapping
    @Operation(summary = "创建 ConfigMap")
    public ResponseData<ConfigMapDTO> create(@RequestBody ConfigMapDTO body) {
        //body 携带 tenantId/clusterId/namespace；K8sClient 将前两者提取进 query（k8s-server 以 query 为准）
        return new ResponseData<>(configMapService.create(body));
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新 ConfigMap")
    public ResponseData<ConfigMapDTO> update(@PathVariable String name, @RequestBody ConfigMapDTO body) {
        body.setName(name);
        return new ResponseData<>(configMapService.update(body));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除 ConfigMap")
    public ResponseData<Void> delete(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        configMapService.delete(name, tenantId, clusterId, namespace);
        return new ResponseData<>();
    }

}
