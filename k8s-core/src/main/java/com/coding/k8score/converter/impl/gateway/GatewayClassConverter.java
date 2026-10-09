package com.coding.k8score.converter.impl.gateway;

import com.coding.common.models.k8s.dto.GatewayClassDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GatewayClassDTO ⇄ GenericKubernetesResource（gateway.networking.k8s.io/v1，集群级）。
 * <p>走 fabric8 通用 CRD API；spec 只有 3 个字段（controllerName / parametersRef / description），
 * 故<b>全建模，无 fetch-overlay</b>（{@code update()} 直接 SSA，不走 convertForUpdate）。
 * <p>cluster-scoped → metadata 不设 namespace。
 */
public class GatewayClassConverter {

    public static final String API_VERSION = "gateway.networking.k8s.io/v1";
    public static final String KIND = "GatewayClass";

    private static final List<String> MODELED_SPEC_KEYS = List.of("controllerName", "parametersRef", "description");

    public GenericKubernetesResource convert(GatewayClassDTO dto) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion(API_VERSION);
        res.setKind(KIND);
        // cluster-scoped：不设 namespace
        res.setMetadata(new ObjectMetaBuilder()
                .withName(dto.getName())
                .withLabels(dto.getLabels())
                .build());

        Map<String, Object> spec = new LinkedHashMap<>();
        GatewaySpecUtil.putStr(spec, "controllerName", dto.getControllerName());
        GatewaySpecUtil.putStr(spec, "description", dto.getDescription());
        GatewaySpecUtil.putNonEmpty(spec, "parametersRef", parametersRefMap(dto.getParametersRef()));

        res.setAdditionalProperty("spec", spec);
        return res;
    }

    @SuppressWarnings("unchecked")
    public GatewayClassDTO revert(GenericKubernetesResource res) {
        GatewayClassDTO dto = new GatewayClassDTO();
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
        Map<String, Object> spec = GatewaySpecUtil.specOf(res);
        if (spec != null) {
            dto.setControllerName(GatewaySpecUtil.asStr(spec.get("controllerName")));
            dto.setDescription(GatewaySpecUtil.asStr(spec.get("description")));
            dto.setParametersRef(fromParametersRef(GatewaySpecUtil.mapOf(spec.get("parametersRef"))));
        }
        dto.setConditions(GatewaySpecUtil.conditions(GatewaySpecUtil.statusOf(res)));
        return dto;
    }

    /**
     * update 专用：spec 无 atomic 子对象，但 {@code parametersRef} 是可选对象 —— 用户清空时应整键移除，
     * 否则 SSA 会把旧值一直留着。做法与 {@code IppoolConverter.convertForUpdate} 同构：
     * 以线上 spec 为底，仅对建模键做 set/clear（未建模键原样保留）。
     * <p>本对象目前无未建模 spec 键，这层仍保留：CRD 升级新增 spec 字段时不需要改代码。
     */
    @SuppressWarnings("unchecked")
    public GenericKubernetesResource convertForUpdate(GatewayClassDTO dto, GenericKubernetesResource live) {
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

    private Map<String, Object> parametersRefMap(GatewayClassDTO.ParametersRef ref) {
        if (ref == null || !GatewaySpecUtil.notBlank(ref.getName())) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        GatewaySpecUtil.putStr(m, "group", ref.getGroup());
        GatewaySpecUtil.putStr(m, "kind", ref.getKind());
        GatewaySpecUtil.putStr(m, "name", ref.getName());
        GatewaySpecUtil.putStr(m, "namespace", ref.getNamespace());
        return m;
    }

    private GatewayClassDTO.ParametersRef fromParametersRef(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        GatewayClassDTO.ParametersRef ref = new GatewayClassDTO.ParametersRef();
        ref.setGroup(GatewaySpecUtil.asStr(m.get("group")));
        ref.setKind(GatewaySpecUtil.asStr(m.get("kind")));
        ref.setName(GatewaySpecUtil.asStr(m.get("name")));
        ref.setNamespace(GatewaySpecUtil.asStr(m.get("namespace")));
        return ref;
    }

}
