package com.coding.k8score.converter.impl.calico;

import com.coding.common.models.k8s.dto.ConditionDTO;
import com.coding.common.models.k8s.dto.IpoolDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IpoolDTO ⇄ GenericKubernetesResource（projectcalico.org/v3 CRD，集群级）。
 * 走 fabric8 通用 CRD API，不引代码生成依赖；spec 以 Map 结构读写。cluster-scoped → metadata 无 namespace。
 */
public class IppoolConverter {

    public static final String API_VERSION = "projectcalico.org/v3";
    public static final String KIND = "IPPool";

    /** 建模的 spec 字段（overlay 时据此 set/clear；不在此列表的 key 视为外部字段，原样保留）。 */
    private static final List<String> MODELED_SPEC_KEYS = List.of(
            "cidr", "blockSize", "nodeSelector", "natOutgoing",
            "disabled", "ipv4hierarchicalPortAllocation", "blocks");

    public GenericKubernetesResource convert(IpoolDTO dto) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion(API_VERSION);
        res.setKind(KIND);
        // cluster-scoped：不设 namespace
        res.setMetadata(new ObjectMetaBuilder()
                .withName(dto.getName())
                .withLabels(dto.getLabels())
                .build());

        Map<String, Object> spec = new LinkedHashMap<>();
        putStr(spec, "cidr", dto.getCidr());
        if (dto.getBlockSize() != null) {
            spec.put("blockSize", dto.getBlockSize());
        }
        if (notEmpty(dto.getNodeSelector())) {
            spec.put("nodeSelector", dto.getNodeSelector());
        }
        putBool(spec, "natOutgoing", dto.getNatOutgoing());
        putBool(spec, "disabled", dto.getDisabled());
        putBool(spec, "ipv4hierarchicalPortAllocation", dto.getIpv4hierarchicalPortAllocation());
        if (notEmpty(dto.getBlocks())) {
            spec.put("blocks", dto.getBlocks());
        }

        res.setAdditionalProperty("spec", spec);
        return res;
    }

    @SuppressWarnings("unchecked")
    public IpoolDTO revert(GenericKubernetesResource res) {
        IpoolDTO dto = new IpoolDTO();
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
        if (spec == null) {
            return dto;
        }
        dto.setCidr(asStr(spec.get("cidr")));
        dto.setBlockSize(asInt(spec.get("blockSize")));
        dto.setNodeSelector(asStringList(spec.get("nodeSelector")));
        dto.setNatOutgoing(asBool(spec.get("natOutgoing")));
        dto.setDisabled(asBool(spec.get("disabled")));
        dto.setIpv4hierarchicalPortAllocation(asBool(spec.get("ipv4hierarchicalPortAllocation")));
        dto.setBlocks(asStringList(spec.get("blocks")));

        Map<String, Object> status = res.getAdditionalProperties() != null
                ? (Map<String, Object>) res.getAdditionalProperties().get("status") : null;
        if (status != null) {
            dto.setConditions(fromConditionList(status.get("conditions")));
        }
        return dto;
    }

    /**
     * update 专用：以线上 spec 为底，仅覆盖建模字段（清空则移除），保留未建模的外部 spec 字段。
     * IPPool spec 无 atomic 复杂子对象，string 列表整体替换即可；status 由 apiserver 维护，不下发。
     * <p>建模字段 set/clear 语义同 {@code ServiceMonitorConverter.overlayEndpoint}：dto 有值→覆盖、清空→移除（交还 Calico 默认）；
     * 未建模 key（Calico 未来新增的 spec 键）原样保留，避免 SSA 丢字段。
     */
    @SuppressWarnings("unchecked")
    public GenericKubernetesResource convertForUpdate(IpoolDTO dto, GenericKubernetesResource live) {
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
                outSpec.remove(key);                  // dto 清空 → 移除，交还 Calico 默认
            }
        }
        res.setAdditionalProperty("spec", outSpec);
        return res;
    }

    // ---------- conditions ----------

    @SuppressWarnings("unchecked")
    private List<ConditionDTO> fromConditionList(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<ConditionDTO> out = new ArrayList<>();
        for (Object o : (List<Object>) raw) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) o;
            ConditionDTO c = new ConditionDTO();
            c.setType(asStr(m.get("type")));
            c.setStatus(asStr(m.get("status")));
            c.setReason(asStr(m.get("reason")));
            c.setMessage(asStr(m.get("message")));
            c.setLastTransitionTime(asStr(m.get("lastTransitionTime")));
            out.add(c);
        }
        return out;
    }

    // ---------- 小工具：Map 写入 / 读取类型安全转换 ----------

    private static void putStr(Map<String, Object> m, String key, String val) {
        if (notBlank(val)) {
            m.put(key, val);
        }
    }

    private static void putBool(Map<String, Object> m, String key, Boolean val) {
        if (val != null) {
            m.put(key, val);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean notEmpty(List<String> list) {
        return list != null && !list.isEmpty();
    }

    private static String asStr(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Boolean asBool(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s);
        }
        return null;
    }

    private static Integer asInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        if (!(o instanceof List)) {
            return null;
        }
        return ((List<Object>) o).stream().map(String::valueOf).toList();
    }

}
