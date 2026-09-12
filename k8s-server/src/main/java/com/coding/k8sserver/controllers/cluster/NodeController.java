package com.coding.k8sserver.controllers.cluster;

import com.coding.common.models.k8s.ResourceType;
import com.coding.common.models.k8s.dto.NodeDTO;
import com.coding.common.models.k8s.dto.NodeDrainResultDTO;
import com.coding.common.models.k8s.dto.NodeEventDTO;
import com.coding.common.models.k8s.dto.NodePodStatDTO;
import com.coding.common.models.k8s.dto.NodeTaintDTO;
import com.coding.common.models.k8s.dto.PodDTO;
import com.coding.common.models.system.ResponseData;
import com.coding.k8score.factory.KubernetesOperationsFactory;
import com.coding.k8score.operations.NamespacedOperations;
import com.coding.k8score.operations.core.CoreV1NodeOperations;
import com.coding.k8sserver.components.ResourceAccessResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * 集群域 - Node（PLATFORM:admin，边界=平台已注册该集群）。
 * <p>节点非标准 CRUD（无 create/delete）：list/get/yaml + cordon/uncordon/drain/label-taint/podstats/pods/events。
 * K8s 语义全在 {@link CoreV1NodeOperations}，本类只做边界校验 + 委托。
 */
@Tag(name = "集群域-Node", description = "节点管理（PLATFORM:admin，边界=集群注册表）")
@RestController
@RequestMapping("/resources/nodes")
public class NodeController {

    private final KubernetesOperationsFactory operationsFactory;
    private final ResourceAccessResolver accessResolver;

    public NodeController(KubernetesOperationsFactory operationsFactory, ResourceAccessResolver accessResolver) {
        this.operationsFactory = operationsFactory;
        this.accessResolver = accessResolver;
    }

    @PostMapping("/list")
    @Operation(summary = "列出节点（body 传 clusterId/labelSelector）")
    public ResponseData<List<NodeDTO>> list(@RequestBody NodeDTO body) {
        String clusterId = body.getClusterId();
        accessResolver.assertClusterAccess(clusterId);
        List<NodeDTO> items = ops(clusterId).list(body.getLabelSelector(), body.getFieldSelector());
        items.forEach(i -> i.setClusterId(clusterId));
        return new ResponseData<>(items);
    }

    @GetMapping("/{name}")
    @Operation(summary = "查询节点")
    public ResponseData<NodeDTO> get(@PathVariable String name, @RequestParam String clusterId) {
        accessResolver.assertClusterAccess(clusterId);
        NodeDTO item = ops(clusterId).get(name);
        if (item != null) {
            item.setClusterId(clusterId);
        }
        return new ResponseData<>(item);
    }

    @GetMapping("/{name}/yaml")
    @Operation(summary = "查询节点 YAML（只读）")
    public ResponseData<String> yaml(@PathVariable String name, @RequestParam String clusterId) {
        accessResolver.assertClusterAccess(clusterId);
        return new ResponseData<>(ops(clusterId).yaml(name));
    }

    @PostMapping("/cordon")
    @Operation(summary = "标记节点不可调度")
    public ResponseData<NodeDTO> cordon(@RequestBody NodeDTO body) {
        accessResolver.assertClusterAccess(body.getClusterId());
        return new ResponseData<>(ops(body.getClusterId()).cordon(body.getName()));
    }

    @PostMapping("/uncordon")
    @Operation(summary = "标记节点可调度")
    public ResponseData<NodeDTO> uncordon(@RequestBody NodeDTO body) {
        accessResolver.assertClusterAccess(body.getClusterId());
        return new ResponseData<>(ops(body.getClusterId()).uncordon(body.getName()));
    }

    @PutMapping("/{name}")
    @Operation(summary = "更新节点标签 + Taint（整体替换）")
    public ResponseData<NodeDTO> updateLabelsTaints(@PathVariable String name, @RequestParam String clusterId,
                                                    @RequestBody NodeLabelTaintRequest req) {
        accessResolver.assertClusterAccess(clusterId);
        return new ResponseData<>(ops(clusterId).updateLabelsTaints(name, req.getLabels(), req.getTaints()));
    }

    @PostMapping("/drain")
    @Operation(summary = "驱逐节点 Pod（保留 DaemonSet/静态/mirror）")
    public ResponseData<NodeDrainResultDTO> drain(@RequestBody NodeDrainRequest req) {
        accessResolver.assertClusterAccess(req.getClusterId());
        return new ResponseData<>(ops(req.getClusterId()).drain(req.getName(),
                Boolean.TRUE.equals(req.getForce()), Boolean.TRUE.equals(req.getDeleteEmptyDir())));
    }

    @GetMapping("/podstats")
    @Operation(summary = "按节点聚合实际 Pod 数 + requests（列表页用）")
    public ResponseData<List<NodePodStatDTO>> podstats(@RequestParam String clusterId) {
        accessResolver.assertClusterAccess(clusterId);
        return new ResponseData<>(ops(clusterId).listPodStats());
    }

    @GetMapping("/{name}/pods")
    @Operation(summary = "列出节点上的 Pod（按 spec.nodeName，跨命名空间）")
    public ResponseData<List<PodDTO>> pods(@PathVariable String name, @RequestParam String clusterId) {
        accessResolver.assertClusterAccess(clusterId);
        NamespacedOperations<PodDTO> podOps = operationsFactory.getAdminNamespacedOperation(ResourceType.POD, clusterId);
        return new ResponseData<>(podOps.list(null, null, "spec.nodeName=" + name));
    }

    @GetMapping("/{name}/events")
    @Operation(summary = "节点事件（involvedObject.kind=Node）")
    public ResponseData<List<NodeEventDTO>> events(@PathVariable String name, @RequestParam String clusterId) {
        accessResolver.assertClusterAccess(clusterId);
        return new ResponseData<>(ops(clusterId).listEvents(name));
    }

    private CoreV1NodeOperations ops(String clusterId) {
        return operationsFactory.getNodeOperation(clusterId);
    }

    /** 标签 + Taint 更新请求体（局部 DTO，不入库）。 */
    public static class NodeLabelTaintRequest {
        private Map<String, String> labels;
        private List<NodeTaintDTO> taints;

        public Map<String, String> getLabels() { return labels; }
        public void setLabels(Map<String, String> labels) { this.labels = labels; }
        public List<NodeTaintDTO> getTaints() { return taints; }
        public void setTaints(List<NodeTaintDTO> taints) { this.taints = taints; }
    }

    /** Drain 请求体。 */
    public static class NodeDrainRequest {
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
