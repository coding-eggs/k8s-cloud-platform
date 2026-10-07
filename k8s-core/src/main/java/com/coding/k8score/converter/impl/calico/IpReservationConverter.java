package com.coding.k8score.converter.impl.calico;

import com.coding.common.models.k8s.dto.IpReservationDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IpReservationDTO ⇄ GenericKubernetesResource（projectcalico.org/v3 CRD，集群级）。★保留 IP
 * 走 fabric8 通用 CRD API；spec 以 Map 结构读写。cluster-scoped → metadata 无 namespace。
 * <p>真实 Calico spec：{@code reservedCIDRs} = CIDR 字符串列表（单 IP = "ip/32"，范围 = "cidr"）。
 * <p><b>部署侧待确认</b>：group(v3)/字段名 reservedCIDRs 以 tigera 文档为准（spec §11）；实现按 projectcalico.org/v3。
 */
public class IpReservationConverter {

    public static final String API_VERSION = "projectcalico.org/v3";
    public static final String KIND = "IPReservation";

    /** 建模的 spec 字段（overlay 时据此 set/clear；不在此列表的 key 视为外部字段，原样保留）。 */
    private static final List<String> MODELED_SPEC_KEYS = List.of("reservedCIDRs");

    public GenericKubernetesResource convert(IpReservationDTO dto) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion(API_VERSION);
        res.setKind(KIND);
        // cluster-scoped：不设 namespace
        res.setMetadata(new ObjectMetaBuilder()
                .withName(dto.getName())
                .withLabels(dto.getLabels())
                .build());

        Map<String, Object> spec = new LinkedHashMap<>();
        List<String> cidrs = normalizeCidrs(dto.getReservedCidrs());
        if (!cidrs.isEmpty()) {
            spec.put("reservedCIDRs", cidrs);
        }

        res.setAdditionalProperty("spec", spec);
        return res;
    }

    @SuppressWarnings("unchecked")
    public IpReservationDTO revert(GenericKubernetesResource res) {
        IpReservationDTO dto = new IpReservationDTO();
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
        Map<String, Object> spec = res.getAdditionalProperties() != null
                ? (Map<String, Object>) res.getAdditionalProperties().get("spec") : null;
        if (spec != null && spec.get("reservedCIDRs") instanceof List<?> list) {
            List<String> cidrs = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o != null) {
                    cidrs.add(String.valueOf(o));
                }
            }
            dto.setReservedCidrs(cidrs);
        }
        return dto;
    }

    /**
     * update 专用：以线上 spec 为底，仅覆盖建模字段（清空则移除），保留未建模的外部 spec 字段。
     * <p>建模字段 set/clear 语义同 {@code IppoolConverter.convertForUpdate}：dto 有值→覆盖、清空→移除；
     * 未建模 key（Calico 未来新增的 spec 键）原样保留，避免 SSA 丢字段。status 由 apiserver 维护，不下发。
     */
    @SuppressWarnings("unchecked")
    public GenericKubernetesResource convertForUpdate(IpReservationDTO dto, GenericKubernetesResource live) {
        GenericKubernetesResource res = convert(dto);
        Map<String, Object> dtoSpec = (Map<String, Object>) res.getAdditionalProperties().get("spec");
        if (dtoSpec == null) {
            dtoSpec = new LinkedHashMap<>();
        }
        // 以线上 spec 为底（保留未建模外部字段），再按建模字段逐个 set/clear
        Map<String, Object> outSpec = new LinkedHashMap<>();
        if (live != null && live.getAdditionalProperties() != null
                && live.getAdditionalProperties().get("spec") instanceof Map) {
            outSpec.putAll((Map<String, Object>) live.getAdditionalProperties().get("spec"));
        }
        for (String key : MODELED_SPEC_KEYS) {
            if (dtoSpec.containsKey(key)) {
                outSpec.put(key, dtoSpec.get(key));   // dto 有值 → 覆盖
            } else {
                outSpec.remove(key);                  // dto 清空 → 移除
            }
        }
        res.setAdditionalProperty("spec", outSpec);
        return res;
    }

    /** 去空白、去重（保序）；过滤空项。 */
    private static List<String> normalizeCidrs(List<String> in) {
        List<String> out = new ArrayList<>();
        if (in == null) {
            return out;
        }
        for (String c : in) {
            if (c != null && !c.isBlank() && !out.contains(c.trim())) {
                out.add(c.trim());
            }
        }
        return out;
    }

}
