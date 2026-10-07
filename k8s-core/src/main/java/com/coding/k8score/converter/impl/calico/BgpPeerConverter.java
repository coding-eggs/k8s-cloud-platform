package com.coding.k8score.converter.impl.calico;

import com.coding.common.models.k8s.dto.BgpPeerDTO;
import com.coding.common.models.k8s.dto.BgpSecretKeyRefDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.asBool;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.asInt;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.asStr;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.asStringList;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.conditions;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.mapOf;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.notEmpty;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.putBool;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.putInt;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.putStr;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.secretRef;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.specOf;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.statusOf;

/**
 * BgpPeerDTO ⇄ GenericKubernetesResource（projectcalico.org/v3 CRD，集群级）。
 * <p>真实 schema 注意：对端地址 {@code peerIP}（非 ip）、{@code nodeSelector} 为字符串、
 * asNumber/localASNumber 为 numorstring → String（写回仍用字符串形态）、sourceAddress 枚举 UseNodeIP/None。
 */
public class BgpPeerConverter {

    public static final String API_VERSION = "projectcalico.org/v3";
    public static final String KIND = "BGPPeer";

    /** 建模的 spec 字段（overlay 时据此 set/clear；不在此列表的 key 视为外部字段，原样保留）。 */
    private static final List<String> MODELED_SPEC_KEYS = List.of(
            "node", "nodeSelector", "peerIP", "asNumber", "localASNumber", "peerSelector",
            "keepOriginalNextHop", "nextHopMode", "password", "sourceAddress",
            "maxRestartTime", "keepaliveTime", "numAllowedLocalASNumbers", "ttlSecurity",
            "reachableBy", "filters", "localWorkloadSelector", "reversePeering");

    public GenericKubernetesResource convert(BgpPeerDTO dto) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion(API_VERSION);
        res.setKind(KIND);
        // cluster-scoped：不设 namespace
        res.setMetadata(new ObjectMetaBuilder()
                .withName(dto.getName())
                .withLabels(dto.getLabels())
                .build());

        Map<String, Object> spec = new LinkedHashMap<>();
        putStr(spec, "node", dto.getNode());
        putStr(spec, "nodeSelector", dto.getNodeSelector());
        putStr(spec, "peerIP", dto.getPeerIp());
        putStr(spec, "asNumber", dto.getAsNumber());
        putStr(spec, "localASNumber", dto.getLocalAsNumber());
        putStr(spec, "peerSelector", dto.getPeerSelector());
        putBool(spec, "keepOriginalNextHop", dto.getKeepOriginalNextHop());
        putStr(spec, "nextHopMode", dto.getNextHopMode());
        Map<String, Object> password = secretRefMap(dto.getPassword());
        if (password != null) {
            spec.put("password", password);
        }
        putStr(spec, "sourceAddress", dto.getSourceAddress());
        putStr(spec, "maxRestartTime", dto.getMaxRestartTime());
        putStr(spec, "keepaliveTime", dto.getKeepaliveTime());
        putInt(spec, "numAllowedLocalASNumbers", dto.getNumAllowedLocalASNumbers());
        putInt(spec, "ttlSecurity", dto.getTtlSecurity());
        putStr(spec, "reachableBy", dto.getReachableBy());
        if (notEmpty(dto.getFilters())) {
            spec.put("filters", dto.getFilters());
        }
        putStr(spec, "localWorkloadSelector", dto.getLocalWorkloadSelector());
        putStr(spec, "reversePeering", dto.getReversePeering());

        res.setAdditionalProperty("spec", spec);
        return res;
    }

    /**
     * update 专用：以线上 spec 为底，仅覆盖建模字段（清空则移除），保留未建模的外部 spec 字段。
     * status 由 apiserver 维护，不下发。
     */
    @SuppressWarnings("unchecked")
    public GenericKubernetesResource convertForUpdate(BgpPeerDTO dto, GenericKubernetesResource live) {
        GenericKubernetesResource res = convert(dto);
        Map<String, Object> dtoSpec = (Map<String, Object>) res.getAdditionalProperties().get("spec");
        if (dtoSpec == null) {
            dtoSpec = new LinkedHashMap<>();
        }
        Map<String, Object> outSpec = new LinkedHashMap<>();
        if (live != null && live.getAdditionalProperties() != null
                && live.getAdditionalProperties().get("spec") instanceof Map) {
            outSpec.putAll((Map<String, Object>) live.getAdditionalProperties().get("spec"));
        }
        for (String key : MODELED_SPEC_KEYS) {
            if (dtoSpec.containsKey(key)) {
                outSpec.put(key, dtoSpec.get(key));   // dto 有值 → 覆盖
            } else {
                outSpec.remove(key);                  // dto 清空 → 移除，交还 Calico 默认
            }
        }
        res.setAdditionalProperty("spec", outSpec);
        return res;
    }

    /** {@code password: {secretKeyRef: {name,namespace,key}}}（引用为空 → null 不写）。 */
    private Map<String, Object> secretRefMap(BgpSecretKeyRefDTO ref) {
        if (ref == null || ref.getName() == null || ref.getName().isBlank()) {
            return null;
        }
        Map<String, Object> inner = new LinkedHashMap<>();
        putStr(inner, "name", ref.getName());
        putStr(inner, "namespace", ref.getNamespace());
        putStr(inner, "key", ref.getKey());
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("secretKeyRef", inner);
        return outer;
    }

    public BgpPeerDTO revert(GenericKubernetesResource res) {
        BgpPeerDTO dto = new BgpPeerDTO();
        if (res == null) {
            return dto;
        }
        if (res.getMetadata() != null) {
            dto.setName(res.getMetadata().getName());
            dto.setLabels(res.getMetadata().getLabels());
            if (res.getMetadata().getCreationTimestamp() != null) {
                dto.setCreationTime(res.getMetadata().getCreationTimestamp());
            }
        }
        Map<String, Object> spec = specOf(res);
        if (spec == null) {
            return dto;
        }
        dto.setNode(asStr(spec.get("node")));
        dto.setNodeSelector(asStr(spec.get("nodeSelector")));
        dto.setPeerIp(asStr(spec.get("peerIP")));
        dto.setAsNumber(asStr(spec.get("asNumber")));
        dto.setLocalAsNumber(asStr(spec.get("localASNumber")));
        dto.setPeerSelector(asStr(spec.get("peerSelector")));
        dto.setKeepOriginalNextHop(asBool(spec.get("keepOriginalNextHop")));
        dto.setNextHopMode(asStr(spec.get("nextHopMode")));
        Map<String, Object> password = mapOf(spec.get("password"));
        if (password != null) {
            dto.setPassword(secretRef(mapOf(password.get("secretKeyRef"))));
        }
        dto.setSourceAddress(asStr(spec.get("sourceAddress")));
        dto.setMaxRestartTime(asStr(spec.get("maxRestartTime")));
        dto.setKeepaliveTime(asStr(spec.get("keepaliveTime")));
        dto.setNumAllowedLocalASNumbers(asInt(spec.get("numAllowedLocalASNumbers")));
        dto.setTtlSecurity(asInt(spec.get("ttlSecurity")));
        dto.setReachableBy(asStr(spec.get("reachableBy")));
        dto.setFilters(asStringList(spec.get("filters")));
        dto.setLocalWorkloadSelector(asStr(spec.get("localWorkloadSelector")));
        dto.setReversePeering(asStr(spec.get("reversePeering")));

        dto.setConditions(conditions(statusOf(res)));
        return dto;
    }

}
