package com.coding.k8score.converter.impl.calico;

import com.coding.common.models.k8s.dto.BgpConfigurationDTO;
import com.coding.common.models.k8s.dto.BgpCommunityDTO;
import com.coding.common.models.k8s.dto.BgpPrefixAdvertisementDTO;
import com.coding.common.models.k8s.dto.BgpSecretKeyRefDTO;
import com.coding.common.models.k8s.dto.BgpServiceBlockDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.asBool;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.asInt;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.asStr;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.asStringList;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.mapOf;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.notEmpty;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.putBool;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.putInt;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.putStr;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.secretRef;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.specOf;

/**
 * BgpConfigurationDTO ⇄ GenericKubernetesResource（projectcalico.org/v3 CRD，集群级）。
 * <p>字段按 v3.28→master 并集建模：revert 缺席=null；convert 只写非 null 字段（旧版 Calico 的结构性
 * schema 会剪掉不认识的键，不会报错）。asNumber 为 numorstring → DTO String，写回仍用字符串形态。
 */
public class BgpConfigurationConverter {

    public static final String API_VERSION = "projectcalico.org/v3";
    public static final String KIND = "BGPConfiguration";

    /** 建模的 spec 字段（overlay 时据此 set/clear；不在此列表的 key 视为外部字段，原样保留）。 */
    private static final List<String> MODELED_SPEC_KEYS = List.of(
            "logSeverityScreen", "nodeToNodeMeshEnabled", "asNumber", "listenPort",
            "serviceClusterIPs", "serviceExternalIPs", "serviceLoadBalancerIPs",
            "communities", "prefixAdvertisements", "nodeMeshPassword", "bindMode", "ignoredInterfaces",
            "serviceLoadBalancerAggregation", "localWorkloadPeeringIPV4", "localWorkloadPeeringIPV6",
            "programClusterRoutes", "ipv4NormalRoutePriority", "ipv6NormalRoutePriority");

    public GenericKubernetesResource convert(BgpConfigurationDTO dto) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion(API_VERSION);
        res.setKind(KIND);
        // cluster-scoped：不设 namespace
        res.setMetadata(new ObjectMetaBuilder()
                .withName(dto.getName())
                .withLabels(dto.getLabels())
                .build());

        Map<String, Object> spec = new LinkedHashMap<>();
        putStr(spec, "logSeverityScreen", dto.getLogSeverityScreen());
        putBool(spec, "nodeToNodeMeshEnabled", dto.getNodeToNodeMeshEnabled());
        putStr(spec, "asNumber", dto.getAsNumber());
        putInt(spec, "listenPort", dto.getListenPort());
        putServiceBlocks(spec, "serviceClusterIPs", dto.getServiceClusterIPs());
        putServiceBlocks(spec, "serviceExternalIPs", dto.getServiceExternalIPs());
        putServiceBlocks(spec, "serviceLoadBalancerIPs", dto.getServiceLoadBalancerIPs());
        putCommunities(spec, "communities", dto.getCommunities());
        putPrefixAds(spec, "prefixAdvertisements", dto.getPrefixAdvertisements());
        Map<String, Object> meshPassword = secretRefMap(dto.getNodeMeshPassword());
        if (meshPassword != null) {
            spec.put("nodeMeshPassword", meshPassword);
        }
        putStr(spec, "bindMode", dto.getBindMode());
        if (notEmpty(dto.getIgnoredInterfaces())) {
            spec.put("ignoredInterfaces", dto.getIgnoredInterfaces());
        }

        // 新版字段（3.28 缺席 → 不写，由结构性 schema 剪除）
        putStr(spec, "serviceLoadBalancerAggregation", dto.getServiceLoadBalancerAggregation());
        putStr(spec, "localWorkloadPeeringIPV4", dto.getLocalWorkloadPeeringIPV4());
        putStr(spec, "localWorkloadPeeringIPV6", dto.getLocalWorkloadPeeringIPV6());
        putStr(spec, "programClusterRoutes", dto.getProgramClusterRoutes());
        putInt(spec, "ipv4NormalRoutePriority", dto.getIpv4NormalRoutePriority());
        putInt(spec, "ipv6NormalRoutePriority", dto.getIpv6NormalRoutePriority());

        res.setAdditionalProperty("spec", spec);
        return res;
    }

    /**
     * update 专用：以线上 spec 为底，仅覆盖建模字段（清空则移除），保留未建模的外部 spec 字段。
     * 子列表（service*IPs/communities/prefixAdvertisements 等）整体替换；status 由 apiserver 维护，不下发。
     */
    @SuppressWarnings("unchecked")
    public GenericKubernetesResource convertForUpdate(BgpConfigurationDTO dto, GenericKubernetesResource live) {
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

    public BgpConfigurationDTO revert(GenericKubernetesResource res) {
        BgpConfigurationDTO dto = new BgpConfigurationDTO();
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
        dto.setLogSeverityScreen(asStr(spec.get("logSeverityScreen")));
        dto.setNodeToNodeMeshEnabled(asBool(spec.get("nodeToNodeMeshEnabled")));
        dto.setAsNumber(asStr(spec.get("asNumber")));
        dto.setListenPort(asInt(spec.get("listenPort")));
        dto.setServiceClusterIPs(serviceBlocks(spec.get("serviceClusterIPs")));
        dto.setServiceExternalIPs(serviceBlocks(spec.get("serviceExternalIPs")));
        dto.setServiceLoadBalancerIPs(serviceBlocks(spec.get("serviceLoadBalancerIPs")));
        dto.setCommunities(communities(spec.get("communities")));
        dto.setPrefixAdvertisements(prefixAds(spec.get("prefixAdvertisements")));
        Map<String, Object> meshPassword = mapOf(spec.get("nodeMeshPassword"));
        if (meshPassword != null) {
            dto.setNodeMeshPassword(secretRef(mapOf(meshPassword.get("secretKeyRef"))));
        }
        dto.setBindMode(asStr(spec.get("bindMode")));
        dto.setIgnoredInterfaces(asStringList(spec.get("ignoredInterfaces")));

        // 新版字段（3.28 缺席 → null）
        dto.setServiceLoadBalancerAggregation(asStr(spec.get("serviceLoadBalancerAggregation")));
        dto.setLocalWorkloadPeeringIPV4(asStr(spec.get("localWorkloadPeeringIPV4")));
        dto.setLocalWorkloadPeeringIPV6(asStr(spec.get("localWorkloadPeeringIPV6")));
        dto.setProgramClusterRoutes(asStr(spec.get("programClusterRoutes")));
        dto.setIpv4NormalRoutePriority(asInt(spec.get("ipv4NormalRoutePriority")));
        dto.setIpv6NormalRoutePriority(asInt(spec.get("ipv6NormalRoutePriority")));

        // status.conditions（v3 无 status 时保持 null）
        dto.setConditions(CalicoSpecUtil.conditions(CalicoSpecUtil.statusOf(res)));
        return dto;
    }

    /** spec.service*IPs：[{cidr}] 列表。 */
    private List<BgpServiceBlockDTO> serviceBlocks(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<BgpServiceBlockDTO> out = new ArrayList<>();
        for (Object o : (List<?>) raw) {
            Map<String, Object> m = mapOf(o);
            if (m == null) {
                continue;
            }
            BgpServiceBlockDTO b = new BgpServiceBlockDTO();
            b.setCidr(asStr(m.get("cidr")));
            out.add(b);
        }
        return out.isEmpty() ? null : out;
    }

    /** spec.communities：[{name,value}] 列表。 */
    private List<BgpCommunityDTO> communities(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<BgpCommunityDTO> out = new ArrayList<>();
        for (Object o : (List<?>) raw) {
            Map<String, Object> m = mapOf(o);
            if (m == null) {
                continue;
            }
            BgpCommunityDTO c = new BgpCommunityDTO();
            c.setName(asStr(m.get("name")));
            c.setValue(asStr(m.get("value")));
            out.add(c);
        }
        return out.isEmpty() ? null : out;
    }

    /** spec.prefixAdvertisements：[{cidr,communities[]}] 列表。 */
    private List<BgpPrefixAdvertisementDTO> prefixAds(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<BgpPrefixAdvertisementDTO> out = new ArrayList<>();
        for (Object o : (List<?>) raw) {
            Map<String, Object> m = mapOf(o);
            if (m == null) {
                continue;
            }
            BgpPrefixAdvertisementDTO p = new BgpPrefixAdvertisementDTO();
            p.setCidr(asStr(m.get("cidr")));
            p.setCommunities(asStringList(m.get("communities")));
            out.add(p);
        }
        return out.isEmpty() ? null : out;
    }

    // ---------- 正向写入辅助（与上方 revert 解析一一对应）----------

    private void putServiceBlocks(Map<String, Object> spec, String key, List<BgpServiceBlockDTO> blocks) {
        if (!notEmpty(blocks)) {
            return;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (BgpServiceBlockDTO b : blocks) {
            Map<String, Object> m = new LinkedHashMap<>();
            putStr(m, "cidr", b.getCidr());
            out.add(m);
        }
        spec.put(key, out);
    }

    private void putCommunities(Map<String, Object> spec, String key, List<BgpCommunityDTO> communities) {
        if (!notEmpty(communities)) {
            return;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (BgpCommunityDTO c : communities) {
            Map<String, Object> m = new LinkedHashMap<>();
            putStr(m, "name", c.getName());
            putStr(m, "value", c.getValue());
            out.add(m);
        }
        spec.put(key, out);
    }

    private void putPrefixAds(Map<String, Object> spec, String key, List<BgpPrefixAdvertisementDTO> ads) {
        if (!notEmpty(ads)) {
            return;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (BgpPrefixAdvertisementDTO p : ads) {
            Map<String, Object> m = new LinkedHashMap<>();
            putStr(m, "cidr", p.getCidr());
            if (notEmpty(p.getCommunities())) {
                m.put("communities", p.getCommunities());
            }
            out.add(m);
        }
        spec.put(key, out);
    }

    /** {@code *Password: {secretKeyRef: {name,namespace,key}}}（引用为空 → null 不写）。 */
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

}
