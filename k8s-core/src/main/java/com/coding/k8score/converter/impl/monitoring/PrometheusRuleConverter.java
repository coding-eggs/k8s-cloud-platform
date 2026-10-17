package com.coding.k8score.converter.impl.monitoring;

import com.coding.common.models.k8s.dto.PrometheusRuleDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PrometheusRuleDTO ⇄ GenericKubernetesResource（monitoring.coreos.com/v1 CRD）。
 *
 * <p>{@code spec.groups} 与 {@code group.rules} 都是 <b>atomic list</b> —— SSA 会把整个列表当一个值拥有。
 * 本类的 {@code convert} 用来建对象（无既有内容，直接覆盖即可）；**改对象必须走
 * {@link #convertForUpdate}**（按名对齐的 fetch-overlay），见那个方法的注释。
 */
public class PrometheusRuleConverter {

    public static final String API_VERSION = "monitoring.coreos.com/v1";

    /** spec.groups[] 的建模字段。overlay 时据此 set/clear；不在此列表的 key 视为外部字段，原样保留。 */
    private static final List<String> MODELED_GROUP_KEYS = List.of(
            "name", "interval", "queryOffset", "limit", "labels", "partialResponseStrategy", "rules");

    /** group.rules[] 的建模字段。 */
    private static final List<String> MODELED_RULE_KEYS = List.of(
            "alert", "record", "expr", "for", "keep_firing_for", "labels", "annotations");

    public GenericKubernetesResource convert(PrometheusRuleDTO dto) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion(API_VERSION);
        res.setKind("PrometheusRule");
        res.setMetadata(new ObjectMetaBuilder()
                .withName(dto.getName())
                .withNamespace(dto.getNamespace())
                .withLabels(dto.getLabels())
                .build());

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("groups", toGroupMaps(dto.getGroups()));   // 必填：空也给空数组
        res.setAdditionalProperty("spec", spec);
        return res;
    }

    @SuppressWarnings("unchecked")
    public PrometheusRuleDTO revert(GenericKubernetesResource res) {
        PrometheusRuleDTO dto = new PrometheusRuleDTO();
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
        Map<String, Object> spec = res.getAdditionalProperties() != null
                ? (Map<String, Object>) res.getAdditionalProperties().get("spec") : null;
        if (spec == null) {
            return dto;
        }
        dto.setGroups(fromGroupMaps(spec.get("groups")));
        return dto;
    }

    // ---------- 模型 → Map ----------

    private List<Map<String, Object>> toGroupMaps(List<PrometheusRuleDTO.Group> groups) {
        if (groups == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PrometheusRuleDTO.Group g : groups) {
            Map<String, Object> m = new LinkedHashMap<>();
            putStr(m, "name", g.getName());
            putStr(m, "interval", g.getInterval());
            putStr(m, "queryOffset", g.getQueryOffset());
            if (g.getLimit() != null) {
                m.put("limit", g.getLimit());
            }
            if (g.getLabels() != null && !g.getLabels().isEmpty()) {
                m.put("labels", g.getLabels());
            }
            putStr(m, "partialResponseStrategy", g.getPartialResponseStrategy());
            m.put("rules", toRuleMaps(g.getRules()));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> toRuleMaps(List<PrometheusRuleDTO.Rule> rules) {
        if (rules == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PrometheusRuleDTO.Rule r : rules) {
            Map<String, Object> m = new LinkedHashMap<>();
            putStr(m, "alert", r.getAlert());
            putStr(m, "record", r.getRecord());
            putStr(m, "expr", r.getExpr());          // 始终 String 写出（线上允许数字，但字符串总是合法的）
            putStr(m, "for", r.getFor_());            // ← Java 字段名 for_，线上键名 for
            putStr(m, "keep_firing_for", r.getKeepFiringFor());
            if (r.getLabels() != null && !r.getLabels().isEmpty()) {
                m.put("labels", r.getLabels());
            }
            if (r.getAnnotations() != null && !r.getAnnotations().isEmpty()) {
                m.put("annotations", r.getAnnotations());
            }
            out.add(m);
        }
        return out;
    }

    // ---------- Map → 模型 ----------

    @SuppressWarnings("unchecked")
    private List<PrometheusRuleDTO.Group> fromGroupMaps(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        List<PrometheusRuleDTO.Group> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) o;
            PrometheusRuleDTO.Group g = new PrometheusRuleDTO.Group();
            g.setName(asStr(m.get("name")));
            g.setInterval(asStr(m.get("interval")));
            g.setQueryOffset(asStr(m.get("queryOffset")));
            g.setLimit(asInt(m.get("limit")));
            g.setLabels(asStringMap(m.get("labels")));
            g.setPartialResponseStrategy(asStr(m.get("partialResponseStrategy")));
            g.setRules(fromRuleMaps(m.get("rules")));
            out.add(g);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<PrometheusRuleDTO.Rule> fromRuleMaps(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        List<PrometheusRuleDTO.Rule> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) o;
            PrometheusRuleDTO.Rule r = new PrometheusRuleDTO.Rule();
            r.setAlert(asStr(m.get("alert")));
            r.setRecord(asStr(m.get("record")));
            r.setExpr(asStr(m.get("expr")));          // ⚠️ 裸数字也要转成串，见测试
            r.setFor_(asStr(m.get("for")));
            r.setKeepFiringFor(asStr(m.get("keep_firing_for")));
            r.setLabels(asStringMap(m.get("labels")));
            r.setAnnotations(asStringMap(m.get("annotations")));
            out.add(r);
        }
        return out;
    }

    // ---------- update：按名对齐的 fetch-overlay ----------

    /**
     * update 专用：{@code spec.groups} 与 {@code group.rules} 都是 atomic list，
     * SSA 重声明会丢弃未建模子字段。故以线上对象为底，**按组名 / 规则名对齐**后逐项覆盖建模字段。
     *
     * <p><b>为什么按名而不是按 index</b>（ServiceMonitor 用的是 index）：组名与规则名在 CRD 里
     * 本来就是唯一键，用户重排、插入、删除都不会错位 —— 而 index 对齐在"中间插一条"时会
     * 把后面所有元素的未建模字段串到错误的对象上。
     *
     * <p>对齐不上的（用户新加的）→ 全新构建；线上有而本次没带的（用户删掉的）→ 丢弃。
     */
    @SuppressWarnings("unchecked")
    public GenericKubernetesResource convertForUpdate(PrometheusRuleDTO dto, GenericKubernetesResource live) {
        GenericKubernetesResource res = convert(dto);
        Map<String, Object> liveSpec = live != null && live.getAdditionalProperties() != null
                ? (Map<String, Object>) live.getAdditionalProperties().get("spec") : null;
        Map<String, Map<String, Object>> liveGroups =
                indexByName(readList(liveSpec, "groups"), m -> asStr(m.get("name")));

        List<Map<String, Object>> merged = new ArrayList<>();
        for (PrometheusRuleDTO.Group g : (dto.getGroups() != null ? dto.getGroups() : List.<PrometheusRuleDTO.Group>of())) {
            Map<String, Object> base = liveGroups.get(g.getName());
            merged.add(overlayGroup(base, g));
        }
        Map<String, Object> spec = (Map<String, Object>) res.getAdditionalProperties().get("spec");
        if (spec != null) {
            spec.put("groups", merged);
        }
        return res;
    }

    /** 以 base（线上组）为底，仅覆盖建模字段；未建模 key 保留。rules 单独按规则名对齐。 */
    private Map<String, Object> overlayGroup(Map<String, Object> base, PrometheusRuleDTO.Group g) {
        Map<String, Object> dtoGroup = toGroupMaps(List.of(g)).get(0);
        Map<String, Object> out = base != null ? new LinkedHashMap<>(base) : new LinkedHashMap<>();
        for (String key : MODELED_GROUP_KEYS) {
            if ("rules".equals(key)) {
                continue;                       // rules 单独处理
            }
            if (dtoGroup.containsKey(key)) {
                out.put(key, dtoGroup.get(key));
            } else {
                out.remove(key);                // 用户清空该字段 → 从对象移除
            }
        }
        Map<String, Map<String, Object>> liveRules =
                indexByName(readList(base, "rules"), PrometheusRuleConverter::ruleKeyOf);
        List<Map<String, Object>> mergedRules = new ArrayList<>();
        for (PrometheusRuleDTO.Rule r : (g.getRules() != null ? g.getRules() : List.<PrometheusRuleDTO.Rule>of())) {
            mergedRules.add(overlayRule(liveRules.get(ruleKey(r)), r));
        }
        out.put("rules", mergedRules);
        return out;
    }

    /** 以 base（线上规则）为底，仅覆盖建模字段；未建模 key 保留。 */
    private Map<String, Object> overlayRule(Map<String, Object> base, PrometheusRuleDTO.Rule r) {
        Map<String, Object> dtoRule = toRuleMaps(List.of(r)).get(0);
        Map<String, Object> out = base != null ? new LinkedHashMap<>(base) : new LinkedHashMap<>();
        for (String key : MODELED_RULE_KEYS) {
            if (dtoRule.containsKey(key)) {
                out.put(key, dtoRule.get(key));
            } else {
                out.remove(key);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readList(Map<String, Object> holder, String key) {
        if (holder == null || !(holder.get(key) instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map) {
                out.add((Map<String, Object>) o);
            }
        }
        return out;
    }

    /** 把线上的一串 Map 按某个键索引起来（键为空则跳过该元素）。 */
    private static Map<String, Map<String, Object>> indexByName(
            List<Map<String, Object>> items, java.util.function.Function<Map<String, Object>, String> keyFn) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> m : items) {
            String k = keyFn.apply(m);
            if (k != null && !k.isBlank()) {
                out.put(k, m);                  // 同名只留最后一个 —— CRD 本就要求唯一，这里只是不炸
            }
        }
        return out;
    }

    /** 线上规则 Map 的对齐键：与 {@link #ruleKey} 同一套前缀，两处共用一个定义。 */
    static String ruleKeyOf(Map<String, Object> m) {
        String alert = asStr(m.get("alert"));
        if (alert != null && !alert.isBlank()) {
            return "alert:" + alert;
        }
        String record = asStr(m.get("record"));
        return record != null && !record.isBlank() ? "record:" + record : "";
    }

    // ---------- 辅助 ----------

    static void putStr(Map<String, Object> m, String key, String v) {
        if (v != null && !v.isBlank()) {
            m.put(key, v);
        }
    }

    /**
     * 读成字符串。**数字也要转** —— CRD 的 {@code expr} 是 IntOrString，
     * 线上真的有 {@code expr: 1} 这种写法，不转就丢类型信息。
     */
    static String asStr(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static Integer asInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Integer.valueOf(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> asStringMap(Object o) {
        if (!(o instanceof Map)) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        ((Map<Object, Object>) o).forEach((k, v) -> out.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        return out.isEmpty() ? null : out;
    }

    /**
     * 规则的**对齐键**：alert 名或 record 名（CRD 要求同一组内唯一）。
     * <p>Task 3 的 overlay 与 Task 11 的运行状态匹配都用它 —— 两处必须是同一个定义，故是 public static。
     */
    public static String ruleKey(PrometheusRuleDTO.Rule r) {
        if (r == null) {
            return "";
        }
        return r.getAlert() != null && !r.getAlert().isBlank() ? "alert:" + r.getAlert()
                : r.getRecord() != null && !r.getRecord().isBlank() ? "record:" + r.getRecord()
                : "";
    }

}
