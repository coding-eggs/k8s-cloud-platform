package com.coding.platformapi.controllers.resource;

import com.coding.common.models.k8s.dto.NodeDTO;
import com.coding.common.models.k8s.dto.NodeDrainResultDTO;
import com.coding.common.models.k8s.dto.NodeEventDTO;
import com.coding.common.models.k8s.dto.NodePodStatDTO;
import com.coding.common.models.k8s.dto.NodeTaintDTO;
import com.coding.common.models.k8s.dto.PodDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.platformapi.k8s.K8sResourceClient;
import com.coding.platformapi.k8s.K8sServerGateway;
import com.coding.platformapi.metrics.MetricsService;
import com.coding.platformapi.metrics.dto.MetricSeriesResponse;
import com.coding.platformapi.metrics.dto.NodeCurrentMetric;
import com.coding.platformapi.metrics.dto.NodeCurrentRequest;
import com.coding.platformapi.metrics.dto.NodeMetricsRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
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
 * 资源管理 - Node（集群级，admin）。list/get/yaml 走通用 {@link K8sResourceClient}；
 * cordon/uncordon/drain/label/podstats/events 为节点专属动作，直连 {@link K8sServerGateway}。
 */
@Tag(name = "资源管理-Node", description = "节点管理（集群级）")
@RestController
@RequestMapping("/resource/nodes")
@RequiredArgsConstructor
public class NodeController {

    private final K8sResourceClient k8s;
    private final K8sServerGateway gateway;
    private final MetricsService metricsService;

    @PostMapping("/list")
    @Operation(summary = "列出节点")
    public ResponseData<List<NodeDTO>> list(@RequestBody NodeDTO body) {
        return new ResponseData<>(k8s.list(body));
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询节点")
    public ResponseData<NodeDTO> get(@PathVariable String name, @RequestParam String clusterId) {
        return new ResponseData<>(k8s.get(dto(name, clusterId)));
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询节点 YAML（只读）")
    public ResponseData<String> yaml(@PathVariable String name, @RequestParam String clusterId) {
        return new ResponseData<>(k8s.yaml(dto(name, clusterId)));
    }

    @PostMapping("/cordon")
    @Operation(summary = "标记节点不可调度")
    public ResponseData<NodeDTO> cordon(@RequestBody NodeDTO body) {
        return new ResponseData<>(gateway.exchange(HttpMethod.POST, "/resources/nodes/cordon", null, body, gateway.responseType(NodeDTO.class)));
    }

    @PostMapping("/uncordon")
    @Operation(summary = "标记节点可调度")
    public ResponseData<NodeDTO> uncordon(@RequestBody NodeDTO body) {
        return new ResponseData<>(gateway.exchange(HttpMethod.POST, "/resources/nodes/uncordon", null, body, gateway.responseType(NodeDTO.class)));
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新节点标签 + Taint（整体替换）")
    public ResponseData<NodeDTO> updateLabelsTaints(@PathVariable String name, @RequestParam String clusterId,
                                                    @RequestBody NodeLabelTaintReq req) {
        return new ResponseData<>(gateway.exchange(HttpMethod.PUT, "/resources/nodes/" + name,
                Map.of("clusterId", clusterId), req, gateway.responseType(NodeDTO.class)));
    }

    @PostMapping("/drain")
    @Operation(summary = "驱逐节点 Pod")
    public ResponseData<NodeDrainResultDTO> drain(@RequestBody NodeDrainReq req) {
        return new ResponseData<>(gateway.exchange(HttpMethod.POST, "/resources/nodes/drain", null, req, gateway.responseType(NodeDrainResultDTO.class)));
    }

    @GetMapping("/podstats")
    @Operation(summary = "按节点聚合实际 Pod 数 + requests")
    public ResponseData<List<NodePodStatDTO>> podstats(@RequestParam String clusterId) {
        return new ResponseData<>(gateway.exchange(HttpMethod.GET, "/resources/nodes/podstats",
                Map.of("clusterId", clusterId), null, gateway.listResponseType(NodePodStatDTO.class)));
    }

    @GetMapping("/{name}/pods")
    @Operation(summary = "列出节点上的 Pod")
    public ResponseData<List<PodDTO>> pods(@PathVariable String name, @RequestParam String clusterId) {
        return new ResponseData<>(gateway.exchange(HttpMethod.GET, "/resources/nodes/" + name + "/pods",
                Map.of("clusterId", clusterId), null, gateway.listResponseType(PodDTO.class)));
    }

    @GetMapping("/{name}/events")
    @Operation(summary = "节点事件")
    public ResponseData<List<NodeEventDTO>> events(@PathVariable String name, @RequestParam String clusterId) {
        return new ResponseData<>(gateway.exchange(HttpMethod.GET, "/resources/nodes/" + name + "/events",
                Map.of("clusterId", clusterId), null, gateway.listResponseType(NodeEventDTO.class)));
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

    private NodeDTO dto(String name, String clusterId) {
        NodeDTO d = new NodeDTO();
        d.setName(name);
        d.setClusterId(clusterId);
        return d;
    }

    /** 标签 + Taint 更新请求体。 */
    public static class NodeLabelTaintReq {
        private Map<String, String> labels;
        private List<NodeTaintDTO> taints;

        public Map<String, String> getLabels() { return labels; }
        public void setLabels(Map<String, String> labels) { this.labels = labels; }
        public List<NodeTaintDTO> getTaints() { return taints; }
        public void setTaints(List<NodeTaintDTO> taints) { this.taints = taints; }
    }

    /** Drain 请求体。 */
    public static class NodeDrainReq {
        private String clusterId;
        private String name;
        private Boolean force;
        private Boolean deleteEmptyDir;

        public String getClusterId() { return clusterId; }
        public void setClusterId(String clusterId) { this.clusterId = clusterId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Boolean getForce() { return force; }
        public void setForce(Boolean force) { this.force = force; }
        public Boolean getDeleteEmptyDir() { return deleteEmptyDir; }
        public void setDeleteEmptyDir(Boolean deleteEmptyDir) { this.deleteEmptyDir = deleteEmptyDir; }
    }
}
