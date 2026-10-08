package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.PodDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.metrics.MetricsService;
import com.coding.platformapi.metrics.dto.MetricSeriesResponse;
import com.coding.platformapi.metrics.dto.WorkloadMetricsRequest;
import com.coding.platformapi.services.PodService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * 资源管理 - Pod（只读 + 删除，无 create/update）。
 * <p>本层只做 HTTP 绑定与 {@link ResponseData} 包装；六操作走 {@link PodService}，
 * 日志的 query 组装在 {@code K8sPodClient}，本层只把响应输出流传进去（分层约定见
 * docs/development/backend-layering.md）。
 */
@Tag(name = "资源管理-Pod", description = "查看/删除命名空间内 Pod（只读）")
@RestController
@RequestMapping("/pods")
@RequiredArgsConstructor
public class PodController {

    private final PodService podService;
    private final MetricsService metricsService;

    @PostMapping("/list")
    @Operation(summary = "列出 Pod")
    public ResponseData<List<PodDTO>> list(@RequestBody PodDTO body) {
        return new ResponseData<>(podService.list(body));
    }

    /**
     * 跨全部命名空间列举（平台侧）。
     * <p>
     * 独立路径的理由同 {@code ConfigMapController#listAll}：权限表按 {@code (方法, 路径)} 定码，
     * 它的码是 {@code platform:pod:list-all}，与租户的 {@code tenant:pod:list} 分开。
     * 这是 Pod 唯一的全局视图入口（按节点看要求先知道节点）。
     */
    @PostMapping("/list-all")
    @Operation(summary = "跨全部命名空间列出 Pod（平台侧）")
    public ResponseData<List<PodDTO>> listAll(@RequestBody PodDTO body) {
        return new ResponseData<>(podService.listAll(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询 Pod")
    public ResponseData<PodDTO> get(@PathVariable String name,
                                    @RequestParam String tenantId,
                                    @RequestParam String clusterId,
                                    @RequestParam String namespace) {
        return new ResponseData<>(podService.get(name, tenantId, clusterId, namespace));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询 Pod YAML（只读展示）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        return new ResponseData<>(podService.yaml(name, tenantId, clusterId, namespace));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除 Pod（由控制器重建）")
    public ResponseData<Void> delete(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        podService.delete(name, tenantId, clusterId, namespace);
        return new ResponseData<>();
    }

    @GetMapping(value = "/{name}/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "获取 Pod 日志（增量轮询 sinceTime；初始化回看 tailLines/sinceSeconds）")
    public void logs(@PathVariable String name,
                     @RequestParam String tenantId,
                     @RequestParam String clusterId,
                     @RequestParam String namespace,
                     @RequestParam(required = false) String container,
                     @RequestParam(required = false) Integer sinceSeconds,
                     @RequestParam(required = false) String sinceTime,
                     @RequestParam(required = false) Integer tailLines,
                     HttpServletResponse response) throws IOException {
        podService.streamLogs(name, tenantId, clusterId, namespace,
                container, sinceSeconds, sinceTime, tailLines, response.getOutputStream());
    }

    // ===== 监控指标（4 个，委托 MetricsService；clusterName 由 clusterId 查库解析）=====

    @PostMapping("/{name}/metrics/cpu")
    @Operation(summary = "Pod CPU 用量（核）")
    public ResponseData<MetricSeriesResponse> cpuMetrics(@PathVariable String name, @RequestBody WorkloadMetricsRequest req) {
        req.setName(name);
        return new ResponseData<>(metricsService.podCpu(req));
    }

    @PostMapping("/{name}/metrics/memory")
    @Operation(summary = "Pod 内存用量（字节）")
    public ResponseData<MetricSeriesResponse> memoryMetrics(@PathVariable String name, @RequestBody WorkloadMetricsRequest req) {
        req.setName(name);
        return new ResponseData<>(metricsService.podMemory(req));
    }

    @PostMapping("/{name}/metrics/network")
    @Operation(summary = "Pod 网络 IO（字节/秒，RX/TX 两条线）")
    public ResponseData<MetricSeriesResponse> networkMetrics(@PathVariable String name, @RequestBody WorkloadMetricsRequest req) {
        req.setName(name);
        return new ResponseData<>(metricsService.podNetwork(req));
    }

    @PostMapping("/{name}/metrics/disk")
    @Operation(summary = "Pod 磁盘 IO（字节/秒，读/写 两条线）")
    public ResponseData<MetricSeriesResponse> diskMetrics(@PathVariable String name, @RequestBody WorkloadMetricsRequest req) {
        req.setName(name);
        return new ResponseData<>(metricsService.podDisk(req));
    }

}
