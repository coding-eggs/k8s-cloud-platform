package com.coding.platformapi.controllers;

import com.coding.common.models.k8s.dto.NodeDTO;
import com.coding.common.models.k8s.dto.NodeDrainResultDTO;
import com.coding.common.models.k8s.dto.NodeEventDTO;
import com.coding.common.models.k8s.dto.NodePodStatDTO;
import com.coding.common.models.k8s.dto.PodDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.metrics.MetricsService;
import com.coding.platformapi.metrics.dto.MetricSeriesResponse;
import com.coding.platformapi.metrics.dto.NodeCurrentMetric;
import com.coding.platformapi.metrics.dto.NodeCurrentRequest;
import com.coding.platformapi.metrics.dto.NodeMetricsRequest;
import com.coding.platformapi.models.NodeDrainRequest;
import com.coding.platformapi.models.NodeLabelTaintRequest;
import com.coding.platformapi.services.NodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 资源管理 - Node（集群级，admin）。本层只做 HTTP 绑定与 {@link ResponseData} 包装；
 * 六端点与节点专属动作（cordon/uncordon/标签污点/drain/podstats/pods/YAML/日志/事件）全在
 * {@link NodeService}（分层约定见 docs/development/backend-layering.md）。
 */
@Tag(name = "资源管理-Node", description = "节点管理（集群级）")
@RestController
@RequestMapping("/nodes")
@RequiredArgsConstructor
public class NodeController {

    private final NodeService nodeService;
    private final MetricsService metricsService;

    @PostMapping("/list")
    @Operation(summary = "列出节点", description = "含按节点聚合的 Pod 数/requests（2 次请求：节点 list + podstats 全量）")
    public ResponseData<List<NodeDTO>> list(@RequestBody NodeDTO body) {
        return new ResponseData<>(nodeService.list(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询节点")
    public ResponseData<NodeDTO> get(@PathVariable String name, @RequestParam String clusterId) {
        return new ResponseData<>(nodeService.get(name, clusterId));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询节点 YAML（只读）")
    public ResponseData<String> yaml(@PathVariable String name, @RequestParam String clusterId) {
        return new ResponseData<>(nodeService.yaml(name, clusterId));
    }

    @PostMapping("/cordon")
    @Operation(summary = "标记节点不可调度")
    public ResponseData<NodeDTO> cordon(@RequestBody NodeDTO body) {
        return new ResponseData<>(nodeService.cordon(body));
    }

    @PostMapping("/uncordon")
    @Operation(summary = "标记节点可调度")
    public ResponseData<NodeDTO> uncordon(@RequestBody NodeDTO body) {
        return new ResponseData<>(nodeService.uncordon(body));
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新节点标签 + Taint（整体替换）")
    public ResponseData<NodeDTO> updateLabelsTaints(@PathVariable String name, @RequestParam String clusterId,
                                                    @RequestBody NodeLabelTaintRequest req) {
        return new ResponseData<>(nodeService.updateLabelsTaints(name, clusterId, req));
    }

    @PostMapping("/drain")
    @Operation(summary = "驱逐节点 Pod")
    public ResponseData<NodeDrainResultDTO> drain(@RequestBody NodeDrainRequest req) {
        return new ResponseData<>(nodeService.drain(req));
    }

    @GetMapping("/podstats")
    @Operation(summary = "按节点聚合实际 Pod 数 + requests")
    public ResponseData<List<NodePodStatDTO>> podstats(@RequestParam String clusterId) {
        return new ResponseData<>(nodeService.podStats(clusterId));
    }

    @GetMapping("/{name}/pods")
    @Operation(summary = "列出节点上的 Pod")
    public ResponseData<List<PodDTO>> pods(@PathVariable String name, @RequestParam String clusterId) {
        return new ResponseData<>(nodeService.pods(name, clusterId));
    }

    @GetMapping("/{name}/pods/{namespace}/{podName}/yaml")
    @Operation(summary = "节点上某 Pod 的 YAML（只读）")
    public ResponseData<String> podYaml(@PathVariable String name, @PathVariable String namespace,
                                        @PathVariable String podName, @RequestParam String clusterId) {
        return new ResponseData<>(nodeService.podYaml(name, namespace, podName, clusterId));
    }

    @GetMapping(value = "/{name}/pods/{namespace}/{podName}/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "节点上某 Pod 的日志（增量轮询 sinceTime；初始化回看 tailLines/sinceSeconds）")
    public void podLogs(@PathVariable String name, @PathVariable String namespace, @PathVariable String podName,
                        @RequestParam String clusterId,
                        @RequestParam(required = false) String container,
                        @RequestParam(required = false) Integer sinceSeconds,
                        @RequestParam(required = false) String sinceTime,
                        @RequestParam(required = false) Integer tailLines,
                        HttpServletResponse response) throws IOException {
        nodeService.streamPodLogs(name, namespace, podName, clusterId,
                container, sinceSeconds, sinceTime, tailLines, response.getOutputStream());
    }

    @GetMapping("/{name}/events")
    @Operation(summary = "节点事件")
    public ResponseData<List<NodeEventDTO>> events(@PathVariable String name, @RequestParam String clusterId) {
        return new ResponseData<>(nodeService.events(name, clusterId));
    }

    // ===== 监控指标（委托 MetricsService；instance = <internalIp>:9100，由前端拼）=====

    @PostMapping("/metrics/current")
    @Operation(summary = "节点当前 CPU%/内存%（列表页批量）")
    public ResponseData<Map<String, NodeCurrentMetric>> currentMetrics(@RequestBody NodeCurrentRequest req) {
        return new ResponseData<>(metricsService.nodeCurrents(req.getClusterId(), req.getInstances()));
    }

    @PostMapping("/{name}/metrics/cpu")
    @Operation(summary = "节点 CPU 使用率（%）")
    public ResponseData<MetricSeriesResponse> cpuMetrics(@PathVariable String name, @RequestBody NodeMetricsRequest req) {
        return new ResponseData<>(metricsService.nodeCpu(req));
    }

    @PostMapping("/{name}/metrics/memory")
    @Operation(summary = "节点内存（字节，Used/Cached/Buffers/Free）")
    public ResponseData<MetricSeriesResponse> memoryMetrics(@PathVariable String name, @RequestBody NodeMetricsRequest req) {
        return new ResponseData<>(metricsService.nodeMemory(req));
    }

    @PostMapping("/{name}/metrics/disk")
    @Operation(summary = "节点磁盘 IO（字节/秒，按设备 读/写）")
    public ResponseData<MetricSeriesResponse> diskMetrics(@PathVariable String name, @RequestBody NodeMetricsRequest req) {
        return new ResponseData<>(metricsService.nodeDisk(req));
    }

    @PostMapping("/{name}/metrics/network")
    @Operation(summary = "节点网络 IO（字节/秒，RX/TX 聚合）")
    public ResponseData<MetricSeriesResponse> networkMetrics(@PathVariable String name, @RequestBody NodeMetricsRequest req) {
        return new ResponseData<>(metricsService.nodeNetwork(req));
    }
}
