package com.coding.platformapi.controllers.resource;

import com.coding.common.models.k8s.dto.HpaDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.k8s.K8sResourceClient;
import com.coding.platformapi.services.HpaService;
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
 * 资源管理 - HPA。透传约定同 ServiceController（k8s-server /resources/hpas）。
 * autoscaling v1/v2 的版本分派在 k8s-server 侧按集群 capability 完成，api 端只透传 DTO。
 */
@Tag(name = "资源管理-HPA", description = "命名空间内 HorizontalPodAutoscaler（v1/v2 由 k8s-server 按集群能力分派）")
@RestController
@RequestMapping("/resource/hpas")
@RequiredArgsConstructor
public class HpaController {

    private final K8sResourceClient k8s;
    private final HpaService hpaService;

    @PostMapping("/list")
    @Operation(summary = "列出 HPA")
    public ResponseData<List<HpaDTO>> list(@RequestBody HpaDTO body) {
        return new ResponseData<>(k8s.list(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询 HPA")
    public ResponseData<HpaDTO> get(@PathVariable String name,
                                    @RequestParam String tenantId,
                                    @RequestParam String clusterId,
                                    @RequestParam String namespace) {
        return new ResponseData<>(k8s.get(dto(name, tenantId, clusterId, namespace)));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询 HPA YAML（只读展示）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        return new ResponseData<>(k8s.yaml(dto(name, tenantId, clusterId, namespace)));
    }

    @PostMapping
    @Operation(summary = "创建 HPA")
    public ResponseData<HpaDTO> create(@RequestBody HpaDTO body) {
        //body 携带 tenantId/clusterId/namespace；K8sResourceClient 将前两者提取进 query（k8s-server 以 query 为准）
        //scaleTargetRef 重复绑定（一负载一 HPA）由 HpaService 校验
        return new ResponseData<>(hpaService.create(body));
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新 HPA")
    public ResponseData<HpaDTO> update(@PathVariable String name, @RequestBody HpaDTO body) {
        body.setName(name);
        return new ResponseData<>(hpaService.update(body));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除 HPA")
    public ResponseData<Void> delete(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        k8s.delete(dto(name, tenantId, clusterId, namespace));
        return new ResponseData<>();
    }

    /**查询 DTO：apiPath 内置于 DTO */
    private HpaDTO dto(String name, String tenantId, String clusterId, String namespace) {
        HpaDTO d = new HpaDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        d.setNamespace(namespace);
        return d;
    }

}
