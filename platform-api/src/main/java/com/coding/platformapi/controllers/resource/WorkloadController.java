package com.coding.platformapi.controllers.resource;

import com.coding.common.models.k8s.dto.WorkloadDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.k8s.K8sResourceClient;
import com.coding.platformapi.metrics.MetricsService;
import com.coding.platformapi.metrics.dto.MetricSeriesResponse;
import com.coding.platformapi.metrics.dto.WorkloadMetricsRequest;
import com.coding.platformapi.services.WorkloadService;
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
 * 资源管理 - 工作负载（Deployment/StatefulSet/DaemonSet，kind 区分）。透传约定同 ConfigMapController。
 */
@Tag(name = "资源管理-工作负载", description = "命名空间内工作负载 Deployment/StatefulSet/DaemonSet")
@RestController
@RequestMapping("/resource/workloads")
@RequiredArgsConstructor
public class WorkloadController {

    private final K8sResourceClient k8s;
    private final WorkloadService workloadService;
    private final MetricsService metricsService;

    @PostMapping("/list")
    @Operation(summary = "列出工作负载（三种 kind 合并）")
    public ResponseData<List<WorkloadDTO>> list(@RequestBody WorkloadDTO body) {
        return new ResponseData<>(workloadService.list(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询工作负载（跨 kind 查找）")
    public ResponseData<WorkloadDTO> get(@PathVariable String name,
                                         @RequestParam String tenantId,
                                         @RequestParam String clusterId,
                                         @RequestParam String namespace) {
        return new ResponseData<>(workloadService.get(dto(name, tenantId, clusterId, namespace)));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询工作负载 YAML（只读展示）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        return new ResponseData<>(k8s.yaml(dto(name, tenantId, clusterId, namespace)));
    }

    @PostMapping
    @Operation(summary = "创建工作负载（kind 在 body）")
    public ResponseData<WorkloadDTO> create(@RequestBody WorkloadDTO body) {
        //body 携带 tenantId/clusterId/namespace/kind；K8sResourceClient 将前两者提取进 query（k8s-server 以 query 为准）
        return new ResponseData<>(workloadService.create(body));
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新工作负载（整 spec 替换，保留 selector/name）")
    public ResponseData<WorkloadDTO> update(@PathVariable String name, @RequestBody WorkloadDTO body) {
        body.setName(name);
        return new ResponseData<>(workloadService.update(body));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除工作负载（跨 kind 查找）")
    public ResponseData<Void> delete(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        k8s.delete(dto(name, tenantId, clusterId, namespace));
        return new ResponseData<>();
    }

    // ===== 监控指标（4 个，委托 MetricsService；clusterName 由 clusterId 查库解析）=====

    @PostMapping("/{name}/metrics/cpu")
    @Operation(summary = "工作负载 CPU 用量（核）")
    public ResponseData<MetricSeriesResponse> cpuMetrics(@PathVariable String name, @RequestBody WorkloadMetricsRequest req) {
        req.setName(name);
        return new ResponseData<>(metricsService.workloadCpu(req));
    }

    @PostMapping("/{name}/metrics/memory")
    @Operation(summary = "工作负载内存用量（字节）")
    public ResponseData<MetricSeriesResponse> memoryMetrics(@PathVariable String name, @RequestBody WorkloadMetricsRequest req) {
        req.setName(name);
        return new ResponseData<>(metricsService.workloadMemory(req));
    }

    @PostMapping("/{name}/metrics/network")
    @Operation(summary = "工作负载网络 IO（字节/秒，RX/TX 两条线）")
    public ResponseData<MetricSeriesResponse> networkMetrics(@PathVariable String name, @RequestBody WorkloadMetricsRequest req) {
        req.setName(name);
        return new ResponseData<>(metricsService.workloadNetwork(req));
    }

    @PostMapping("/{name}/metrics/disk")
    @Operation(summary = "工作负载磁盘 IO（字节/秒，读/写 两条线）")
    public ResponseData<MetricSeriesResponse> diskMetrics(@PathVariable String name, @RequestBody WorkloadMetricsRequest req) {
        req.setName(name);
        return new ResponseData<>(metricsService.workloadDisk(req));
    }

    /**查询 DTO：apiPath 内置于 DTO */
    private WorkloadDTO dto(String name, String tenantId, String clusterId, String namespace) {
        WorkloadDTO d = new WorkloadDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        d.setNamespace(namespace);
        return d;
    }

}
