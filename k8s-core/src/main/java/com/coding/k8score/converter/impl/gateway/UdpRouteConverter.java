package com.coding.k8score.converter.impl.gateway;

import com.coding.common.models.k8s.dto.UdpRouteDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UdpRouteDTO ⇄ GenericKubernetesResource（gateway.networking.k8s.io，命名空间级）。
 * <p>与 {@link TcpRouteConverter} 逐行同构（spec 只有 parentRefs + rules{name, backendRefs}），
 * 差别只有协议与 CRD kind。之所以不抽公共基类：本模块的 converter 一律"一资源一类"（同
 * ServiceMonitor/PodMonitor、Calico 各 converter），类名本身是工厂 switch 的落点；
 * 共享部分已在 {@link GatewaySpecUtil}（parentRefs / backendRefs / L4 rules / status）。
 * <p>CRD 版本语义（v1 优先、v1alpha2 回退，两者同结构）见 {@link TcpRouteConverter} 的类注释。
 */
public class UdpRouteConverter {

    public static final String GROUP = "gateway.networking.k8s.io";
    public static final String KIND = "UDPRoute";

    private static final List<String> MODELED_SPEC_KEYS = List.of("parentRefs", "rules");

    private final String crdVersion;

    public UdpRouteConverter(String crdVersion) {
        this.crdVersion = crdVersion;
    }

    public String crdVersion() {
        return crdVersion;
    }

    public String apiVersion() {
        return GROUP + "/" + crdVersion;
    }

    public GenericKubernetesResource convert(UdpRouteDTO dto) {
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
        GatewaySpecUtil.putNonEmpty(spec, "rules", GatewaySpecUtil.toL4RuleMaps(dto.getRules()));

        res.setAdditionalProperty("spec", spec);
        return res;
    }

    public UdpRouteDTO revert(GenericKubernetesResource res) {
        UdpRouteDTO dto = new UdpRouteDTO();
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
            dto.setRules(GatewaySpecUtil.l4Rules(spec));
        }
        dto.setParentStatuses(GatewaySpecUtil.parentStatuses(GatewaySpecUtil.statusOf(res)));
        return dto;
    }

    public GenericKubernetesResource convertForUpdate(UdpRouteDTO dto, GenericKubernetesResource live) {
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
