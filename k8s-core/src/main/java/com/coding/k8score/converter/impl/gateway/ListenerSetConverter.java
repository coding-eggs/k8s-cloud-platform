package com.coding.k8score.converter.impl.gateway;

import com.coding.common.models.k8s.dto.ConditionDTO;
import com.coding.common.models.k8s.dto.ListenerSetDTO;
import com.coding.k8score.converter.MetaUtil;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GenericKubernetesResource → ListenerSetDTO（gateway.networking.k8s.io/v1，命名空间级）。
 * <p><b>只有反向</b>：ListenerSet 在平台里只读（写由应用发布经 k8s-server {@code /application/ingress-listenerset} 完成）。
 */
public class ListenerSetConverter {

    public static final String API_VERSION = "gateway.networking.k8s.io/v1";
    public static final String KIND = "ListenerSet";

    public ListenerSetDTO revert(GenericKubernetesResource res) {
        ListenerSetDTO dto = new ListenerSetDTO();
        if (res == null) {
            return dto;
        }
        if (res.getMetadata() != null) {
            dto.setName(res.getMetadata().getName());
            dto.setNamespace(res.getMetadata().getNamespace());
            dto.setLabels(res.getMetadata().getLabels());
            dto.setAnnotations(MetaUtil.annotations(res.getMetadata()));
            dto.setOwnerReferences(MetaUtil.ownerRefs(res.getMetadata()));
            dto.setCreationTime(res.getMetadata().getCreationTimestamp());
        }
        Map<String, Object> spec = GatewaySpecUtil.specOf(res);
        if (spec != null) {
            Map<String, Object> parent = GatewaySpecUtil.mapOf(spec.get("parentRef"));
            if (parent != null) {
                dto.setParentName(GatewaySpecUtil.asStr(parent.get("name")));
                String ns = GatewaySpecUtil.asStr(parent.get("namespace"));
                dto.setParentNamespace(ns == null || ns.isBlank() ? dto.getNamespace() : ns);
            }
            dto.setListeners(listeners(spec.get("listeners")));
        }
        Map<String, Object> status = GatewaySpecUtil.statusOf(res);
        if (status != null) {
            dto.setConditions(GatewaySpecUtil.conditions(status));
            dto.setListenerStatuses(listenerStatuses(status.get("listeners")));
        }
        return dto;
    }

    private static List<ListenerSetDTO.Listener> listeners(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<ListenerSetDTO.Listener> out = new ArrayList<>();
        for (Map<String, Object> m : GatewaySpecUtil.listOf(raw)) {
            ListenerSetDTO.Listener l = new ListenerSetDTO.Listener();
            l.setName(GatewaySpecUtil.asStr(m.get("name")));
            l.setHostname(GatewaySpecUtil.asStr(m.get("hostname")));
            l.setPort(GatewaySpecUtil.asInt(m.get("port")));
            l.setProtocol(GatewaySpecUtil.asStr(m.get("protocol")));
            out.add(l);
        }
        return out.isEmpty() ? null : out;
    }

    private static List<ListenerSetDTO.ListenerStatus> listenerStatuses(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<ListenerSetDTO.ListenerStatus> out = new ArrayList<>();
        for (Map<String, Object> m : GatewaySpecUtil.listOf(raw)) {
            ListenerSetDTO.ListenerStatus s = new ListenerSetDTO.ListenerStatus();
            s.setName(GatewaySpecUtil.asStr(m.get("name")));
            s.setAttachedRoutes(GatewaySpecUtil.asInt(m.get("attachedRoutes")));
            if (m.get("conditions") instanceof List) {
                List<ConditionDTO> conds = new ArrayList<>();
                for (Map<String, Object> cm : GatewaySpecUtil.listOf(m.get("conditions"))) {
                    conds.add(GatewaySpecUtil.toCondition(cm));
                }
                s.setConditions(conds.isEmpty() ? null : conds);
            }
            out.add(s);
        }
        return out.isEmpty() ? null : out;
    }
}
