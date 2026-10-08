package com.coding.platformapi.services;

import com.coding.common.models.k8s.dto.NodeDTO;
import com.coding.common.models.k8s.dto.NodeDrainResultDTO;
import com.coding.common.models.k8s.dto.NodeEventDTO;
import com.coding.common.models.k8s.dto.NodePodStatDTO;
import com.coding.common.models.k8s.dto.PodDTO;
import com.coding.platformapi.k8s.K8sClient;
import com.coding.platformapi.k8s.K8sNodeClient;
import com.coding.platformapi.models.NodeDrainRequest;
import com.coding.platformapi.models.NodeLabelTaintRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 节点业务层：六端点（list/get/yaml）走 {@link K8sClient}；cordon / uncordon / 标签污点 / drain / podstats /
 * 节点上的 Pod 与 YAML / 流式日志 / 事件 走 {@link K8sNodeClient}。
 * <p>
 * <b>本层的业务规则</b>：把节点列表与按节点聚合的 Pod 数/requests（podstats）join 成一行视图。
 * podstats 的聚合留在 k8s-server 侧——它需要 admin client 的全命名空间视野（按 {@code spec.nodeName}
 * 跨 ns 统计），platform-api 侧逐命名空间拉取会退化成 N×M 次请求。故这里是「取两次 + 内存 join」。
 * <p>
 * 分层约定见 docs/development/backend-layering.md。
 */
@Service
@RequiredArgsConstructor
public class NodeService {

    private final K8sClient k8s;
    private final K8sNodeClient nodeClient;

    /**
     * 节点列表 + Pod 统计。
     * <p>
     * <b>请求次数不变式：2 次</b>（1 次节点 list + 1 次 podstats 全量）。
     * 修复前这里是 N+1：先查了一次 podstats 建 map 却未使用（死代码），随后在循环里逐节点调用一个
     * 每次内部重新发 podstats 请求的方法 —— 10 个节点 = 11 次 HTTP。现在 podstats 只取一次，
     * 按 nodeName 建索引后在内存里逐节点回填。
     */
    public List<NodeDTO> list(NodeDTO body) {
        List<NodeDTO> nodes = k8s.list(body);
        Map<String, NodePodStatDTO> stats = podStatsByName(body.getClusterId());
        for (NodeDTO node : nodes) {
            fillPodStatus(node, stats);
        }
        return nodes;
    }

    /**单节点详情 + Pod 统计（同样 2 次请求：节点 get + podstats 全量） */
    public NodeDTO get(String nodeName, String clusterId) {
        NodeDTO node = k8s.get(dto(nodeName, clusterId));
        if (node == null) {
            return null;
        }
        fillPodStatus(node, podStatsByName(clusterId));
        return node;
    }

    /**节点 YAML（只读） */
    public String yaml(String nodeName, String clusterId) {
        return k8s.yaml(dto(nodeName, clusterId));
    }

    // ==================== 节点专属动作（委托 K8sNodeClient） ====================

    public NodeDTO cordon(NodeDTO body) {
        return nodeClient.cordon(body);
    }

    public NodeDTO uncordon(NodeDTO body) {
        return nodeClient.uncordon(body);
    }

    public NodeDTO updateLabelsTaints(String name, String clusterId, NodeLabelTaintRequest req) {
        return nodeClient.updateLabelsTaints(name, clusterId, req);
    }

    public NodeDrainResultDTO drain(NodeDrainRequest req) {
        return nodeClient.drain(req);
    }

    /**按节点聚合实际 Pod 数 + requests（列表页批量用） */
    public List<NodePodStatDTO> podStats(String clusterId) {
        return nodeClient.podStats(clusterId);
    }

    /**列出节点上的 Pod（按 spec.nodeName，跨命名空间） */
    public List<PodDTO> pods(String name, String clusterId) {
        return nodeClient.pods(name, clusterId);
    }

    /**节点上某 Pod 的 YAML（只读） */
    public String podYaml(String name, String namespace, String podName, String clusterId) {
        return nodeClient.podYaml(name, namespace, podName, clusterId);
    }

    /**节点事件 */
    public List<NodeEventDTO> events(String name, String clusterId) {
        return nodeClient.events(name, clusterId);
    }

    /**节点上某 Pod 的日志（流式透传）：写入调用方提供的响应输出流 */
    public void streamPodLogs(String name, String namespace, String podName, String clusterId,
                              String container, Integer sinceSeconds, String sinceTime, Integer tailLines,
                              OutputStream out) {
        nodeClient.streamPodLogs(name, namespace, podName, clusterId, container, sinceSeconds, sinceTime, tailLines, out);
    }

    // ==================== 内部 ====================

    /**podstats 全量拉取并按 nodeName 建索引（一次请求，供 N 个节点复用；重名取先到者） */
    private Map<String, NodePodStatDTO> podStatsByName(String clusterId) {
        return nodeClient.podStats(clusterId).stream()
                .collect(Collectors.toMap(NodePodStatDTO::getNodeName, Function.identity(), (a, b) -> a));
    }

    /**把该节点的聚合统计回填到 DTO；统计里没有该节点（如刚加入）则字段保持 null */
    private void fillPodStatus(NodeDTO node, Map<String, NodePodStatDTO> stats) {
        NodePodStatDTO stat = stats.get(node.getName());
        if (stat == null) {
            return;
        }
        node.setPodCount(stat.getPodCount());
        node.setCpuRequestMillicores(stat.getCpuRequestMillicores());
        node.setMemRequestBytes(stat.getMemRequestBytes());
    }

    /**查询 DTO：apiPath 内置于 DTO（集群级，无 namespace） */
    private NodeDTO dto(String name, String clusterId) {
        NodeDTO d = new NodeDTO();
        d.setName(name);
        d.setClusterId(clusterId);
        return d;
    }
}
