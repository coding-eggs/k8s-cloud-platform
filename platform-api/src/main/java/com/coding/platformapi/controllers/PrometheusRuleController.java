package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.PrometheusRuleDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.services.PrometheusRuleService;
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
 * 资源管理 - PrometheusRule（CRD，集群未装 Prometheus Operator 时透传 404）。
 * <p>本层只做 HTTP 绑定与 {@link ResponseData} 包装；内容校验与 {@code labels.namespace} 代打在
 * {@link PrometheusRuleService}（分层约定见 docs/development/backend-layering.md）。
 */
@Tag(name = "资源管理-PrometheusRule", description = "命名空间内 PrometheusRule")
@RestController
@RequestMapping("/prometheusrules")
@RequiredArgsConstructor
public class PrometheusRuleController {

    private final PrometheusRuleService ruleService;

    @PostMapping("/list")
    @Operation(summary = "列出 PrometheusRule")
    public ResponseData<List<PrometheusRuleDTO>> list(@RequestBody PrometheusRuleDTO body) {
        return new ResponseData<>(ruleService.list(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询 PrometheusRule")
    public ResponseData<PrometheusRuleDTO> get(@PathVariable String name,
                                               @RequestParam String tenantId,
                                               @RequestParam String clusterId,
                                               @RequestParam String namespace) {
        return new ResponseData<>(ruleService.get(name, tenantId, clusterId, namespace));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询 PrometheusRule YAML（只读展示）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        return new ResponseData<>(ruleService.yaml(name, tenantId, clusterId, namespace));
    }

    @PostMapping
    @Operation(summary = "创建 PrometheusRule")
    public ResponseData<PrometheusRuleDTO> create(@RequestBody PrometheusRuleDTO body) {
        return new ResponseData<>(ruleService.create(body));
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新 PrometheusRule")
    public ResponseData<PrometheusRuleDTO> update(@PathVariable String name, @RequestBody PrometheusRuleDTO body) {
        body.setName(name);
        return new ResponseData<>(ruleService.update(body));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除 PrometheusRule")
    public ResponseData<Void> delete(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        ruleService.delete(name, tenantId, clusterId, namespace);
        return new ResponseData<>();
    }

}
