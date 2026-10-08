package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.HpaDTO;
import com.coding.common.models.system.ResponseData;
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
 * 资源管理 - HPA。本层只做 HTTP 绑定与 {@link ResponseData} 包装。
 * autoscaling v1/v2 的版本分派在 k8s-server 侧按集群 capability 完成；「一负载一 HPA」硬校验在
 * {@link HpaService}（分层约定见 docs/development/backend-layering.md）。
 */
@Tag(name = "资源管理-HPA", description = "命名空间内 HorizontalPodAutoscaler（v1/v2 由 k8s-server 按集群能力分派）")
@RestController
@RequestMapping("/hpas")
@RequiredArgsConstructor
public class HpaController {

    private final HpaService hpaService;

    @PostMapping("/list")
    @Operation(summary = "列出 HPA")
    public ResponseData<List<HpaDTO>> list(@RequestBody HpaDTO body) {
        return new ResponseData<>(hpaService.list(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询 HPA")
    public ResponseData<HpaDTO> get(@PathVariable String name,
                                    @RequestParam String tenantId,
                                    @RequestParam String clusterId,
                                    @RequestParam String namespace) {
        return new ResponseData<>(hpaService.get(name, tenantId, clusterId, namespace));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询 HPA YAML（只读展示）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        return new ResponseData<>(hpaService.yaml(name, tenantId, clusterId, namespace));
    }

    @PostMapping
    @Operation(summary = "创建 HPA")
    public ResponseData<HpaDTO> create(@RequestBody HpaDTO body) {
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
        hpaService.delete(name, tenantId, clusterId, namespace);
        return new ResponseData<>();
    }

}
