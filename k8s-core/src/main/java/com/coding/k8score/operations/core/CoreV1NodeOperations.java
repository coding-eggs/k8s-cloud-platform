package com.coding.k8score.operations.core;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.NodeDTO;
import com.coding.common.models.k8s.dto.NodeDrainResultDTO;
import com.coding.common.models.k8s.dto.NodeEventDTO;
import com.coding.common.models.k8s.dto.NodePodStatDTO;
import com.coding.common.models.k8s.dto.NodeTaintDTO;
import com.coding.k8score.converter.CommonConverter;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeSpec;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Taint;
import io.fabric8.kubernetes.api.model.TaintBuilder;
import io.fabric8.kubernetes.api.model.policy.v1.Eviction;
import io.fabric8.kubernetes.api.model.policy.v1.EvictionBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * core/v1 Node 操作（集群级）。节点无平台侧 create/delete；提供 cordon/uncordon、drain、标签+Taint 编辑、
 * 按节点 Pod 聚合、节点事件。所有 K8s 语义都收在这里，k8s-server 只做边界透传。
 */
public class CoreV1NodeOperations {

    private final KubernetesClient client;
    private final CommonConverter<Node, NodeDTO> converter;

    public CoreV1NodeOperations(KubernetesClient client, CommonConverter<Node, NodeDTO> converter) {
        this.client = client;
        this.converter = converter;
    }

    public List<NodeDTO> list(String labelSelector, String fieldSelector) {
        ListOptions o = new ListOptions();
        if (StringUtils.hasText(labelSelector)) {
            o.setLabelSelector(labelSelector);
        }
        if (StringUtils.hasText(fieldSelector)) {
            o.setFieldSelector(fieldSelector);
        }
        return client.nodes().list(o).getItems().stream().map(converter::revert).toList();
    }

    public NodeDTO get(String name) {
        requireName(name);
        return converter.revert(client.nodes().withName(name).get());
    }

    public String yaml(String name) {
        requireName(name);
        Node n = client.nodes().withName(name).get();
        if (n != null && n.getMetadata() != null) {
            n.getMetadata().setManagedFields(null);
        }
        return Serialization.asYaml(n);
    }

    public NodeDTO cordon(String name) {
        return setUnschedulable(name, true);
    }

    public NodeDTO uncordon(String name) {
        return setUnschedulable(name, false);
    }

    private NodeDTO setUnschedulable(String name, boolean v) {
        requireName(name);
        Node updated = client.nodes().withName(name).edit(n -> {
            if (n.getSpec() == null) {
                n.setSpec(new NodeSpec());
            }
            n.getSpec().setUnschedulable(v);
            return n;
        });
        return converter.revert(updated);
    }

    /** 整体替换标签 + Taint（前端提交全量）。 */
    public NodeDTO updateLabelsTaints(String name, Map<String, String> labels, List<NodeTaintDTO> taints) {
        requireName(name);
        Node updated = client.nodes().withName(name).edit(n -> {
            n.getMetadata().setLabels(labels != null ? new LinkedHashMap<>(labels) : new LinkedHashMap<>());
            if (n.getSpec() == null) {
                n.setSpec(new NodeSpec());
            }
            List<Taint> ts = new ArrayList<>();
            if (taints != null) {
                for (NodeTaintDTO t : taints) {
                    ts.add(new TaintBuilder().withKey(t.getKey()).withValue(t.getValue()).withEffect(t.getEffect()).build());
                }
            }
            n.getSpec().setTaints(ts);
            return n;
        });
        return converter.revert(updated);
    }

    /** 驱逐节点上的 Pod（保留 DaemonSet/静态/mirror）。force=true 时 PDB 阻断改为直接 delete。 */
    public NodeDrainResultDTO drain(String name, boolean force, boolean deleteEmptyDir) {
        requireName(name);
        List<Pod> pods = client.pods().inAnyNamespace()
                .list(new ListOptionsBuilder().withFieldSelector("spec.nodeName=" + name).build()).getItems();
        NodeDrainResultDTO result = new NodeDrainResultDTO();
        for (Pod pod : pods) {
            String ns = pod.getMetadata().getNamespace();
            String pn = pod.getMetadata().getName();
            String key = ns + "/" + pn;
            String skip = skipReason(pod);
            if (skip != null) {
                result.getSkipped().add(key + ": " + skip);
                continue;
            }
            try {
                if (force) {
                    client.pods().inNamespace(ns).withName(pn).delete();
                } else {
                    evict(ns, pn);
                }
                result.getEvicted().add(key);
            } catch (Exception e) {
                if (force) {
                    try {
                        client.pods().inNamespace(ns).withName(pn).delete();
                        result.getEvicted().add(key);
                    } catch (Exception e2) {
                        result.getErrors().add(key + ": " + e2.getMessage());
                    }
                } else {
                    result.getErrors().add(key + ": " + e.getMessage());
                }
            }
        }
        return result;
    }

    private void evict(String ns, String name) {
        Eviction ev = new EvictionBuilder()
                .withNewMetadata().withName(name).withNamespace(ns).endMetadata()
                .build();
        client.pods().inNamespace(ns).withName(name).evict(ev);
    }

    /** 返回跳过原因；null = 应驱逐。 */
    private String skipReason(Pod pod) {
        var md = pod.getMetadata();
        if (md.getAnnotations() != null && md.getAnnotations().containsKey("kubernetes.io/mirror")) {
            return "mirror-pod";
        }
        boolean staticPod = md.getOwnerReferences() == null || md.getOwnerReferences().isEmpty();
        if (staticPod) {
            return "static-pod";
        }
        for (var ref : md.getOwnerReferences()) {
            if ("DaemonSet".equals(ref.getKind())) {
                return "daemonset";
            }
        }
        return null;
    }

    /** 集群级 pod 列表按 nodeName 聚合：count + cpu/mem requests 之和。 */
    public List<NodePodStatDTO> listPodStats() {
        return aggregate(client.pods().inAnyNamespace().list().getItems());
    }

    /** 包内可见，便于单测：按 nodeName 聚合 count + cpu/mem requests（不依赖 client）。 */
    List<NodePodStatDTO> aggregate(List<Pod> pods) {
        Map<String, long[]> byNode = new ConcurrentHashMap<>(); // [count, cpuMilli, memBytes]
        for (Pod pod : pods) {
            if (pod.getSpec() == null || !StringUtils.hasText(pod.getSpec().getNodeName())) {
                continue;
            }
            String nodeName = pod.getSpec().getNodeName();
            long[] acc = byNode.computeIfAbsent(nodeName, k -> new long[3]);
            acc[0]++;
            List<Container> containers = pod.getSpec().getContainers();
            if (containers == null) {
                continue;
            }
            for (Container c : containers) {
                var req = c.getResources() != null ? c.getResources().getRequests() : null;
                if (req == null) {
                    continue;
                }
                acc[1] += cpuToMilli(req.get("cpu"));
                acc[2] += memToBytes(req.get("memory"));
            }
        }
        List<NodePodStatDTO> out = new ArrayList<>();
        byNode.forEach((node, a) -> {
            NodePodStatDTO s = new NodePodStatDTO();
            s.setNodeName(node);
            s.setPodCount(a[0]);
            s.setCpuRequestMillicores(a[1]);
            s.setMemRequestBytes(a[2]);
            out.add(s);
        });
        return out;
    }

    /** "200m"→200；"1"→1000；"0.5"→500。milli 判定看 format（"m"），amount 是纯数字。 */
    private long cpuToMilli(Quantity q) {
        if (q == null) {
            return 0L;
        }
        if ("m".equals(q.getFormat())) {
            return Long.parseLong(q.getAmount());
        }
        return (long) Math.round(Double.parseDouble(q.getAmount()) * 1000);
    }

    private long memToBytes(Quantity q) {
        if (q == null) {
            return 0L;
        }
        String f = q.getFormat();
        double n = Double.parseDouble(q.getAmount());
        return switch (f == null ? "" : f) {
            case "m" -> (long) (n / 1024);
            case "k", "K" -> (long) (n * 1024);
            case "M", "Mi" -> (long) (n * 1024 * 1024);
            case "G", "Gi" -> (long) (n * 1024L * 1024 * 1024);
            case "T", "Ti" -> (long) (n * 1024L * 1024 * 1024 * 1024);
            default -> (long) n; // 纯字节
        };
    }

    /** 节点事件：查 involvedObject.name=&lt;name&gt;（跨命名空间），过滤 kind=Node。 */
    public List<NodeEventDTO> listEvents(String name) {
        requireName(name);
        var op = client.v1().events().inAnyNamespace();
        List<Event> events;
        try {
            events = op.withField("involvedObject.name", name).list().getItems();
        } catch (Exception e) {
            events = op.list().getItems(); // 老集群不支持该 fieldSelector 时退回全量再过滤
        }
        List<NodeEventDTO> out = new ArrayList<>();
        for (Event e : events) {
            if (e.getInvolvedObject() == null || !"Node".equals(e.getInvolvedObject().getKind())) {
                continue;
            }
            NodeEventDTO d = new NodeEventDTO();
            d.setReason(e.getReason());
            d.setMessage(e.getMessage());
            d.setType(e.getType());
            d.setCount(e.getCount());
            if (e.getFirstTimestamp() != null) {
                d.setFirstTimestamp(e.getFirstTimestamp());
            }
            if (e.getLastTimestamp() != null) {
                d.setLastTimestamp(e.getLastTimestamp());
            }
            out.add(d);
        }
        return out;
    }

    private void requireName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new CloudPlatformException(EnumResponseType.SEARCH_K8S_REQUIRE_NAME_AND_NAMESPACE);
        }
    }
}
