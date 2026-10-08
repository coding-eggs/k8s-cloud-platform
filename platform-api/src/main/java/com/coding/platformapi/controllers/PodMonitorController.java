package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.PodMonitorDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.models.PodMonitorKeyRequest;
import com.coding.platformapi.services.PodMonitorService;
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
import java.util.Map;

/**
 * 资源管理 - PodMonitor（CRD，集群未装 Prometheus Operator 时透传 404）。
 * <p>本层只做 HTTP 绑定与 {@link ResponseData} 包装；podRef → matchLabels 的绑定解析与 k8s-server
 * 调用在 {@link PodMonitorService}（分层约定见 docs/development/backend-layering.md）。
 */
@Tag(name = "资源管理-PodMonitor", description = "命名空间内 PodMonitor")
@RestController
@RequestMapping("/podmonitors")
@RequiredArgsConstructor
public class PodMonitorController {

    private final PodMonitorService pmService;

    @PostMapping("/list")
    @Operation(summary = "列出 PodMonitor")
    public ResponseData<List<PodMonitorDTO>> list(@RequestBody PodMonitorDTO body) {
        return new ResponseData<>(pmService.list(body));
    }

    @PostMapping("/relabel-labels")
    @Operation(summary = "拉取 Relabeling/MetricRelabeling 的 sourceLabels 候选（编辑态，来自集群 Prometheus discovery；不可达返回空）")
    public ResponseData<Map<String, List<String>>> relabelLabels(@RequestBody PodMonitorKeyRequest req) {
        return new ResponseData<>(pmService.relabelLabels(req.getClusterId(), req.getNamespace(), req.getName()));
    }

    @PostMapping("/metric-names")
    @Operation(summary = "拉取 MetricRelabeling 的 __name__（指标名）候选（编辑态，scoped 到本 PM 活跃 target；无 target/不可达返回空）")
    public ResponseData<List<String>> metricNames(@RequestBody PodMonitorKeyRequest req) {
        return new ResponseData<>(pmService.metricNames(req.getClusterId(), req.getNamespace(), req.getName()));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询 PodMonitor")
    public ResponseData<PodMonitorDTO> get(@PathVariable String name,
                                           @RequestParam String tenantId,
                                           @RequestParam String clusterId,
                                           @RequestParam String namespace) {
        return new ResponseData<>(pmService.get(name, tenantId, clusterId, namespace));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询 PodMonitor YAML（只读展示）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        return new ResponseData<>(pmService.yaml(name, tenantId, clusterId, namespace));
    }

    @PostMapping
    @Operation(summary = "创建 PodMonitor")
    public ResponseData<PodMonitorDTO> create(@RequestBody PodMonitorDTO body) {
        return new ResponseData<>(pmService.create(body));
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新 PodMonitor")
    public ResponseData<PodMonitorDTO> update(@PathVariable String name, @RequestBody PodMonitorDTO body) {
        body.setName(name);
        return new ResponseData<>(pmService.update(body));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除 PodMonitor")
    public ResponseData<Void> delete(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        pmService.delete(name, tenantId, clusterId, namespace);
        return new ResponseData<>();
    }

}
