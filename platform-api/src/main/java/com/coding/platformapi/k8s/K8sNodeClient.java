package com.coding.platformapi.k8s;

import com.coding.common.models.k8s.dto.NodeDTO;
import com.coding.common.models.k8s.dto.NodeEventDTO;
import com.coding.common.models.k8s.dto.NodePodStatDTO;
import com.coding.common.models.k8s.dto.NodeDrainResultDTO;
import com.coding.common.models.k8s.dto.PodDTO;
import com.coding.platformapi.models.NodeDrainRequest;
import com.coding.platformapi.models.NodeLabelTaintRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * k8s-server 节点专用端点 client（{@code /nodes/**} 中非六端点形态的部分）：
 * cordon / uncordon / 标签污点更新 / drain / podstats / 节点上的 Pod 列表与 YAML / 流式日志 / 事件，
 * 一个方法一个端点，纯传输零业务。
 * <p>
 * 节点六端点（list/get/yaml）仍在 {@link K8sClient}（DTO 驱动）。日志的流式透传（不整体缓冲）是本 client
 * 与其他方法的唯一形态差异：以 {@code OutputStream} 出参，调用方（controller）只提供响应流。
 */
@Component
@RequiredArgsConstructor
public class K8sNodeClient {

    private final K8sServerGateway gateway;

    /**标记节点不可调度：POST /nodes/cordon（body 带 clusterId/name） */
    public NodeDTO cordon(NodeDTO body) {
        return gateway.exchange(HttpMethod.POST, "/nodes/cordon", null, body,
                gateway.responseType(NodeDTO.class));
    }

    /**标记节点可调度：POST /nodes/uncordon */
    public NodeDTO uncordon(NodeDTO body) {
        return gateway.exchange(HttpMethod.POST, "/nodes/uncordon", null, body,
                gateway.responseType(NodeDTO.class));
    }

    /**更新节点标签 + Taint（整体替换）：PUT /nodes/{name}?clusterId */
    public NodeDTO updateLabelsTaints(String name, String clusterId, NodeLabelTaintRequest req) {
        return gateway.exchange(HttpMethod.PUT, "/nodes/" + name, Map.of("clusterId", clusterId), req,
                gateway.responseType(NodeDTO.class));
    }

    /**驱逐节点 Pod（保留 DaemonSet/静态/mirror）：POST /nodes/drain */
    public NodeDrainResultDTO drain(NodeDrainRequest req) {
        return gateway.exchange(HttpMethod.POST, "/nodes/drain", null, req,
                gateway.responseType(NodeDrainResultDTO.class));
    }

    /**按节点聚合实际 Pod 数 + requests：GET /nodes/podstats?clusterId（列表页批量用） */
    public List<NodePodStatDTO> podStats(String clusterId) {
        return gateway.exchange(HttpMethod.GET, "/nodes/podstats", Map.of("clusterId", clusterId), null,
                gateway.listResponseType(NodePodStatDTO.class));
    }

    /**列出节点上的 Pod（按 spec.nodeName，跨命名空间）：GET /nodes/{name}/pods?clusterId */
    public List<PodDTO> pods(String name, String clusterId) {
        return gateway.exchange(HttpMethod.GET, "/nodes/" + name + "/pods", Map.of("clusterId", clusterId), null,
                gateway.listResponseType(PodDTO.class));
    }

    /**节点上某 Pod 的 YAML（只读）：GET /nodes/{name}/pods/{namespace}/{podName}/yaml?clusterId */
    public String podYaml(String name, String namespace, String podName, String clusterId) {
        return gateway.exchange(HttpMethod.GET,
                "/nodes/" + name + "/pods/" + namespace + "/" + podName + "/yaml",
                Map.of("clusterId", clusterId), null, gateway.responseType(String.class));
    }

    /**节点事件（involvedObject.kind=Node）：GET /nodes/{name}/events?clusterId */
    public List<NodeEventDTO> events(String name, String clusterId) {
        return gateway.exchange(HttpMethod.GET, "/nodes/" + name + "/events", Map.of("clusterId", clusterId), null,
                gateway.listResponseType(NodeEventDTO.class));
    }

    /**
     * 节点上某 Pod 的日志（流式透传，不整体缓冲）：
     * GET /nodes/{name}/pods/{namespace}/{podName}/logs?clusterId[&container][&sinceTime|tailLines|sinceSeconds]
     */
    public void streamPodLogs(String name, String namespace, String podName, String clusterId,
                              String container, Integer sinceSeconds, String sinceTime, Integer tailLines,
                              OutputStream out) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("clusterId", clusterId);
        StreamQueryParams.putLogOptions(params, container, sinceSeconds, sinceTime, tailLines);
        gateway.streamGet("/nodes/" + name + "/pods/" + namespace + "/" + podName + "/logs", params, out);
    }
}
