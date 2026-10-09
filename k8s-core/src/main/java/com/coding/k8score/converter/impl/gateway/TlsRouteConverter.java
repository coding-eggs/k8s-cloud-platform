package com.coding.k8score.converter.impl.gateway;

import com.coding.common.models.k8s.dto.TlsRouteDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TlsRouteDTO ⇄ GenericKubernetesResource（gateway.networking.k8s.io，命名空间级）。
 * <p>比 TCP/UDPRoute 多一个 {@code spec.hostnames}（SNI 名）。<b>SNI 不在 rules 里</b> ——
 * 所有 Gateway API v1.x 版本（含 v1alpha2）都用 {@code spec.hostnames}，旧实验 API 的
 * {@code rules[].matches[].sniHostname} 已不存在（见 {@code TlsRouteDTO} 的类注释）。
 * <p>CRD 版本语义见 {@link TcpRouteConverter} 的类注释。
 */
public class TlsRouteConverter {

    public static final String GROUP = "gateway.networking.k8s.io";
    public static final String KIND = "TLSRoute";

    private static final List<String> MODELED_SPEC_KEYS = List.of("parentRefs", "hostnames", "rules");

    private final String crdVersion;

    public TlsRouteConverter(String crdVersion) {
        this.crdVersion = crdVersion;
    }

    public String crdVersion() {
        return crdVersion;
    }

    public String apiVersion() {
        return GROUP + "/" + crdVersion;
    }

    public GenericKubernetesResource convert(TlsRouteDTO dto) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion(apiVersion());
        res.setKind(KIND);
        res.setMetadata(new ObjectMetaBuilder()
                .withName(dto.getName())
                .withNamespace(dto.getNamespace())
                .withLabels(dto.getLabels())
                .build());

        Map<String, Object> spec = new LinkedHashMap<>();
        GatewaySpecUtil.putNonEmpty(spec, "parentRefs", GatewaySpecUtil.toParentRefMaps(dto.getParentRefs()));
        GatewaySpecUtil.putNonEmpty(spec, "hostnames", dto.getHostnames());
        GatewaySpecUtil.putNonEmpty(spec, "rules", GatewaySpecUtil.toL4RuleMaps(dto.getRules()));

        res.setAdditionalProperty("spec", spec);
        return res;
    }

    public TlsRouteDTO revert(GenericKubernetesResource res) {
        TlsRouteDTO dto = new TlsRouteDTO();
        if (res == null) {
            return dto;
        }
        if (res.getMetadata() != null) {
            dto.setName(res.getMetadata().getName());
            dto.setNamespace(res.getMetadata().getNamespace());
            dto.setLabels(res.getMetadata().getLabels());
            if (res.getMetadata().getCreationTimestamp() != null) {
                dto.setCreationTime(res.getMetadata().getCreationTimestamp());
            }
        }
        Map<String, Object> spec = GatewaySpecUtil.specOf(res);
        if (spec != null) {
            dto.setParentRefs(GatewaySpecUtil.parentRefs(spec));
            dto.setHostnames(GatewaySpecUtil.asStringList(spec.get("hostnames")));
            dto.setRules(GatewaySpecUtil.l4Rules(spec));
        }
        dto.setParentStatuses(GatewaySpecUtil.parentStatuses(GatewaySpecUtil.statusOf(res)));
        return dto;
    }

    public GenericKubernetesResource convertForUpdate(TlsRouteDTO dto, GenericKubernetesResource live) {
        GenericKubernetesResource res = convert(dto);
        Map<String, Object> dtoSpec = GatewaySpecUtil.mapOf(res.getAdditionalProperties().get("spec"));
        if (dtoSpec == null) {
            dtoSpec = new LinkedHashMap<>();
        }
        Map<String, Object> outSpec = new LinkedHashMap<>();
        Map<String, Object> liveSpec = GatewaySpecUtil.specOf(live);
        if (liveSpec != null) {
            outSpec.putAll(liveSpec);
        }
        for (String key : MODELED_SPEC_KEYS) {
            if (dtoSpec.containsKey(key)) {
                outSpec.put(key, dtoSpec.get(key));
            } else {
                outSpec.remove(key);
            }
        }
        res.setAdditionalProperty("spec", outSpec);
        return res;
    }

}
