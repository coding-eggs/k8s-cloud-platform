package com.coding.k8score.converter.impl.core;

import com.coding.common.models.k8s.dto.NodeConditionDTO;
import com.coding.common.models.k8s.dto.NodeDTO;
import com.coding.common.models.k8s.dto.NodeTaintDTO;
import com.coding.k8score.converter.CommonConverter;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Quantity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * core/v1 Node DTO 转换器（集群级）。
 * <p>节点不支持平台侧整体 create；{@link #convert} 仅保留 name（供 updateLabelsTaints 定位对象），
 * 实际改由 operations 在 live 对象上 edit。{@link #revert} 做 fabric8 Node → DTO 的完整映射。
 * <p>注意 fabric8 7.6.x 的 {@code NodeStatus} 未建模 podCIDR（无 getter），走 {@code additionalProperties} 兜底读取；
 * taints 在 {@code spec} 上而非 status。
 */
public class CoreV1NodeConverter implements CommonConverter<Node, NodeDTO> {

    @Override
    public Node convert(NodeDTO dto) {
        return new NodeBuilder()
                .withMetadata(new ObjectMetaBuilder().withName(dto.getName()).build())
                .build();
    }

    @Override
    public NodeDTO revert(Node n) {
        if (n == null) {
            return null;
        }
        NodeDTO d = new NodeDTO();
        var md = n.getMetadata();
        if (md != null) {
            d.setName(md.getName());
            d.setLabels(md.getLabels() != null ? new LinkedHashMap<>(md.getLabels()) : new LinkedHashMap<>());
            if (md.getCreationTimestamp() != null) {
                d.setCreationTime(md.getCreationTimestamp());
            }
            d.setRoles(rolesFromLabels(md.getLabels()));
            if (md.getLabels() != null && md.getLabels().get("kubernetes.io/os") != null) {
                d.setOs(md.getLabels().get("kubernetes.io/os"));
            }
        }
        var spec = n.getSpec();
        if (spec != null) {
            d.setUnschedulable(spec.getUnschedulable());
            if (spec.getTaints() != null) {
                List<NodeTaintDTO> ts = new ArrayList<>();
                for (var t : spec.getTaints()) {
                    NodeTaintDTO td = new NodeTaintDTO();
                    td.setKey(t.getKey());
                    td.setValue(t.getValue());
                    td.setEffect(t.getEffect());
                    ts.add(td);
                }
                d.setTaints(ts);
            }
        }
        var st = n.getStatus();
        if (st != null) {
            var info = st.getNodeInfo();
            if (info != null) {
                d.setKubeletVersion(info.getKubeletVersion());
                d.setArch(info.getArchitecture());
                d.setKernelVersion(info.getKernelVersion());
                d.setContainerRuntimeVersion(info.getContainerRuntimeVersion());
                d.setOsImage(info.getOsImage());
            }
            d.setPodCidr(additionalString(st, "podCIDR"));
            if (st.getAddresses() != null) {
                for (var a : st.getAddresses()) {
                    if ("InternalIP".equals(a.getType())) {
                        d.setInternalIp(a.getAddress());
                    } else if ("ExternalIP".equals(a.getType())) {
                        d.setExternalIp(a.getAddress());
                    }
                }
            }
            Map<String, Quantity> cap = st.getCapacity() != null ? st.getCapacity() : Map.of();
            Map<String, Quantity> alloc = st.getAllocatable() != null ? st.getAllocatable() : Map.of();
            d.setCpuCapacity(q(cap.get("cpu")));
            d.setMemoryCapacity(q(cap.get("memory")));
            d.setCpuAllocatable(q(alloc.get("cpu")));
            d.setMemoryAllocatable(q(alloc.get("memory")));
            if (alloc.get("pods") != null) {
                d.setPodsLimit(Long.parseLong(alloc.get("pods").getAmount()));
            }
            if (st.getConditions() != null) {
                List<NodeConditionDTO> conds = new ArrayList<>();
                for (var c : st.getConditions()) {
                    NodeConditionDTO cd = new NodeConditionDTO();
                    cd.setType(c.getType());
                    cd.setStatus(c.getStatus());
                    cd.setReason(c.getReason());
                    cd.setMessage(c.getMessage());
                    cd.setLastHeartbeatTime(c.getLastHeartbeatTime());
                    cd.setLastTransitionTime(c.getLastTransitionTime());
                    conds.add(cd);
                    if ("Ready".equals(c.getType())) {
                        d.setStatus(c.getStatus());
                    }
                }
                d.setConditions(conds);
            }
        }
        return d;
    }

    /** node-role.kubernetes.io/&lt;role&gt; 的 &lt;role&gt; 集合。 */
    private List<String> rolesFromLabels(Map<String, String> labels) {
        if (labels == null) {
            return new ArrayList<>();
        }
        List<String> roles = new ArrayList<>();
        for (String k : labels.keySet()) {
            if (k.startsWith("node-role.kubernetes.io/")) {
                roles.add(k.substring("node-role.kubernetes.io/".length()));
            }
        }
        return roles;
    }

    /** 读未建模字段（如 podCIDR）：fabric8 把 JSON 里无对应 Java 属性的字段放进 additionalProperties。 */
    private String additionalString(io.fabric8.kubernetes.api.model.NodeStatus st, String key) {
        Map<String, Object> extra = st.getAdditionalProperties();
        if (extra == null) {
            return null;
        }
        Object v = extra.get(key);
        return v != null ? v.toString() : null;
    }

    private String q(Quantity quantity) {
        return quantity == null ? null : quantity.toString();
    }
}
