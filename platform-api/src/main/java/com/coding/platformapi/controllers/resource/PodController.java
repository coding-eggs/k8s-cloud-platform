package com.coding.platformapi.controllers.resource;

import com.coding.common.models.k8s.dto.PodDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.k8s.K8sResourceClient;
import com.coding.platformapi.k8s.K8sServerGateway;
import com.coding.platformapi.metrics.MetricsService;
import com.coding.platformapi.metrics.dto.MetricSeriesResponse;
import com.coding.platformapi.metrics.dto.WorkloadMetricsRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资源管理 - Pod（只读 + 删除，无 create/update）。透传约定同 ConfigMapController。
 * 日志走 HTTP 流式透传（k8s-server text/plain → 原样写到响应流，不整体缓冲），属特殊端点直连 gateway。
 */
@Tag(name = "资源管理-Pod", description = "查看/删除命名空间内 Pod（只读）")
@RestController
@RequestMapping("/resource/pods")
@RequiredArgsConstructor
public class PodController {

    private final K8sResourceClient k8s;
    private final K8sServerGateway gateway;
    private final MetricsService metricsService;

    @PostMapping("/list")
    @Operation(summary = "列出 Pod")
    public ResponseData<List<PodDTO>> list(@RequestBody PodDTO body) {
        return new ResponseData<>(k8s.list(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询 Pod")
    public ResponseData<PodDTO> get(@PathVariable String name,
                                    @RequestParam String tenantId,
                                    @RequestParam String clusterId,
                                    @RequestParam String namespace) {
        return new ResponseData<>(k8s.get(dto(name, tenantId, clusterId, namespace)));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询 Pod YAML（只读展示）")
    public ResponseData<String> yaml(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        return new ResponseData<>(k8s.yaml(dto(name, tenantId, clusterId, namespace)));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "删除 Pod（由控制器重建）")
    public ResponseData<Void> delete(@PathVariable String name,
                                     @RequestParam String tenantId,
                                     @RequestParam String clusterId,
                                     @RequestParam String namespace) {
        k8s.delete(dto(name, tenantId, clusterId, namespace));
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
        Map<String, String> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        params.put("clusterId", clusterId);
        params.put("namespace", namespace);
        if (StringUtils.hasText(container)) {
            params.put("container", container);
        }
        // sinceTime（增量轮询）> tailLines（初始化回看最近 N 行）> sinceSeconds（旧时间窗），三选一透传
        if (sinceTime != null) {
            params.put("sinceTime", sinceTime);
        } else if (tailLines != null) {
            params.put("tailLines", String.valueOf(tailLines));
        } else if (sinceSeconds != null) {
            params.put("sinceSeconds", String.valueOf(sinceSeconds));
        }
        gateway.streamGet("/resources/pods/" + name + "/logs", params, response.getOutputStream());
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

    /**查询 DTO：apiPath 内置于 DTO */
    private PodDTO dto(String name, String tenantId, String clusterId, String namespace) {
        PodDTO d = new PodDTO();
        d.setName(name);
        d.setTenantId(tenantId);
        d.setClusterId(clusterId);
        d.setNamespace(namespace);
        return d;
    }

}
