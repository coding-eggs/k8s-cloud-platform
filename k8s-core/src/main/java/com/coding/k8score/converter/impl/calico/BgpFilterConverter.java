package com.coding.k8score.converter.impl.calico;

import com.coding.common.models.k8s.dto.BgpFilterDTO;
import com.coding.common.models.k8s.dto.BgpFilterOperationDTO;
import com.coding.common.models.k8s.dto.BgpFilterRuleDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.asInt;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.asStr;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.asStringList;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.conditions;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.mapOf;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.notEmpty;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.putInt;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.putStr;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.specOf;
import static com.coding.k8score.converter.impl.calico.CalicoSpecUtil.statusOf;

/**
 * BgpFilterDTO ⇄ GenericKubernetesResource（projectcalico.org/v3 CRD，集群级）。
 * <p>真实 schema（v3.28 起）= {@code spec.{exportV4,importV4,exportV6,importV6}}
 * 四条规则列表；规则内 prefixLength{min,max} / communities{values[]} / operations 判别联合，
 * 与 revert 的展示向扁平化一一对应。真实 JSON key {@code interface}（Java 保留字）→ DTO 字段 {@code iface}。
 */
public class BgpFilterConverter {

    public static final String API_VERSION = "projectcalico.org/v3";
    public static final String KIND = "BGPFilter";

    /** 建模的 spec 字段（四条规则列表；overlay 时整体替换，清空则移除）。 */
    private static final List<String> MODELED_SPEC_KEYS = List.of("exportV4", "importV4", "exportV6", "importV6");

    public GenericKubernetesResource convert(BgpFilterDTO dto) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion(API_VERSION);
        res.setKind(KIND);
        // cluster-scoped：不设 namespace
        res.setMetadata(new ObjectMetaBuilder()
                .withName(dto.getName())
                .withLabels(dto.getLabels())
                .build());

        Map<String, Object> spec = new LinkedHashMap<>();
        putRules(spec, "exportV4", dto.getExportV4());
        putRules(spec, "importV4", dto.getImportV4());
        putRules(spec, "exportV6", dto.getExportV6());
        putRules(spec, "importV6", dto.getImportV6());

        res.setAdditionalProperty("spec", spec);
        return res;
    }

    /**
     * update 专用：以线上 spec 为底，四条规则列表整体替换（清空则移除），保留未建模的外部 spec 字段。
     * status 由 apiserver 维护，不下发。
     */
    @SuppressWarnings("unchecked")
    public GenericKubernetesResource convertForUpdate(BgpFilterDTO dto, GenericKubernetesResource live) {
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
                outSpec.put(key, dtoSpec.get(key));   // dto 有值 → 整体覆盖
            } else {
                outSpec.remove(key);                  // dto 清空 → 移除
            }
        }
        res.setAdditionalProperty("spec", outSpec);
        return res;
    }

    // ---------- 正向写入辅助（与下方 rules/operations 解析一一对应）----------

    private void putRules(Map<String, Object> spec, String key, List<BgpFilterRuleDTO> rules) {
        if (!notEmpty(rules)) {
            return;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (BgpFilterRuleDTO r : rules) {
            Map<String, Object> m = new LinkedHashMap<>();
            putStr(m, "cidr", r.getCidr());
            if (r.getPrefixLengthMin() != null || r.getPrefixLengthMax() != null) {
                Map<String, Object> pl = new LinkedHashMap<>();
                putInt(pl, "min", r.getPrefixLengthMin());
                putInt(pl, "max", r.getPrefixLengthMax());
                m.put("prefixLength", pl);
            }
            putStr(m, "source", r.getSource());
            putStr(m, "interface", r.getIface()); // 真实 JSON key = "interface"
            putStr(m, "matchOperator", r.getMatchOperator());
            putStr(m, "peerType", r.getPeerType());
            if (notEmpty(r.getCommunityValues())) {
                Map<String, Object> comm = new LinkedHashMap<>();
                comm.put("values", r.getCommunityValues());
                m.put("communities", comm);
            }
            if (notEmpty(r.getAsPathPrefix())) {
                m.put("asPathPrefix", r.getAsPathPrefix());
            }
            putInt(m, "priority", r.getPriority());
            putStr(m, "action", r.getAction());
            List<Map<String, Object>> ops = operationMaps(r.getOperations());
            if (ops != null) {
                m.put("operations", ops);
            }
            out.add(m);
        }
        spec.put(key, out);
    }

    /** operations 判别联合 → 每项恰写一个操作键（addCommunity{value} / prependASPath{prefix[]} / setPriority{value}）。 */
    private List<Map<String, Object>> operationMaps(List<BgpFilterOperationDTO> ops) {
        if (!notEmpty(ops)) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (BgpFilterOperationDTO op : ops) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (op.getAddCommunity() != null && !op.getAddCommunity().isBlank()) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("value", op.getAddCommunity());
                m.put("addCommunity", v);
            }
            if (notEmpty(op.getPrependAsPath())) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("prefix", op.getPrependAsPath());
                m.put("prependASPath", v);
            }
            if (op.getSetPriority() != null) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("value", op.getSetPriority());
                m.put("setPriority", v);
            }
            out.add(m);
        }
        return out;
    }

    public BgpFilterDTO revert(GenericKubernetesResource res) {
        BgpFilterDTO dto = new BgpFilterDTO();
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
        dto.setExportV4(rules(spec.get("exportV4")));
        dto.setImportV4(rules(spec.get("importV4")));
        dto.setExportV6(rules(spec.get("exportV6")));
        dto.setImportV6(rules(spec.get("importV6")));

        dto.setConditions(conditions(statusOf(res)));
        return dto;
    }

    /** 规则列表（v4/v6 结构相同，共用解析）。 */
    private List<BgpFilterRuleDTO> rules(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<BgpFilterRuleDTO> out = new ArrayList<>();
        for (Object o : (List<?>) raw) {
            Map<String, Object> m = mapOf(o);
            if (m == null) {
                continue;
            }
            BgpFilterRuleDTO r = new BgpFilterRuleDTO();
            r.setCidr(asStr(m.get("cidr")));
            Map<String, Object> pl = mapOf(m.get("prefixLength"));
            if (pl != null) {
                r.setPrefixLengthMin(asInt(pl.get("min")));
                r.setPrefixLengthMax(asInt(pl.get("max")));
            }
            r.setSource(asStr(m.get("source")));
            r.setIface(asStr(m.get("interface"))); // 真实 JSON key = "interface"
            r.setMatchOperator(asStr(m.get("matchOperator")));
            r.setPeerType(asStr(m.get("peerType")));
            Map<String, Object> comm = mapOf(m.get("communities"));
            if (comm != null) {
                r.setCommunityValues(asStringList(comm.get("values")));
            }
            r.setAsPathPrefix(asStringList(m.get("asPathPrefix")));
            r.setPriority(asInt(m.get("priority")));
            r.setAction(asStr(m.get("action")));
            r.setOperations(operations(m.get("operations")));
            out.add(r);
        }
        return out.isEmpty() ? null : out;
    }

    /** operations 判别联合 → 扁平化（每项恰设一个操作字段）。 */
    private List<BgpFilterOperationDTO> operations(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<BgpFilterOperationDTO> out = new ArrayList<>();
        for (Object o : (List<?>) raw) {
            Map<String, Object> m = mapOf(o);
            if (m == null) {
                continue;
            }
            BgpFilterOperationDTO op = new BgpFilterOperationDTO();
            Map<String, Object> addCommunity = mapOf(m.get("addCommunity"));
            if (addCommunity != null) {
                op.setAddCommunity(asStr(addCommunity.get("value")));
            }
            Map<String, Object> prepend = mapOf(m.get("prependASPath"));
            if (prepend != null) {
                op.setPrependAsPath(asStringList(prepend.get("prefix")));
            }
            Map<String, Object> setPriority = mapOf(m.get("setPriority"));
            if (setPriority != null) {
                op.setSetPriority(asInt(setPriority.get("value")));
            }
            out.add(op);
        }
        return out.isEmpty() ? null : out;
    }

}
