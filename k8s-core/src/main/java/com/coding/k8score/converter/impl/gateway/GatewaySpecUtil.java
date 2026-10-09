package com.coding.k8score.converter.impl.gateway;

import com.coding.common.models.k8s.dto.BackendRefDTO;
import com.coding.common.models.k8s.dto.ConditionDTO;
import com.coding.common.models.k8s.dto.L4RouteRuleDTO;
import com.coding.common.models.k8s.dto.ParentRefDTO;
import com.coding.common.models.k8s.dto.RouteParentStatusDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gateway API 各 converter 的共享 spec-Map 读写小工具（风格同 {@link com.coding.k8score.converter.impl.calico.CalicoSpecUtil}）。
 * <p>spec 一律以 {@code Map<String,Object>} 读写（fabric8 通用 CRD API，无代码生成依赖）。
 * <p>这里放的是<b>跨 Route 类型共用</b>的部分：parentRefs / backendRefs / conditions / status.parents。
 * GatewayClass / Gateway / HTTPRoute 三个 converter 各自再写自己的私有部分。
 */
final class GatewaySpecUtil {

    /** Route 类型列表（allowedRoutes.kinds 校验、UI 下拉候选共用） */
    static final List<String> ROUTE_KINDS = List.of("HTTPRoute", "GRPCRoute", "TCPRoute", "TLSRoute", "UDPRoute");

    private GatewaySpecUtil() {
    }

    // ---------- 读（Map → DTO） ----------

    /** spec 子 Map（res/properties/spec 任一为 null → null）。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> specOf(GenericKubernetesResource res) {
        if (res == null || res.getAdditionalProperties() == null) {
            return null;
        }
        Object spec = res.getAdditionalProperties().get("spec");
        return spec instanceof Map ? (Map<String, Object>) spec : null;
    }

    /** status 子 Map（无 status → null）。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> statusOf(GenericKubernetesResource res) {
        if (res == null || res.getAdditionalProperties() == null) {
            return null;
        }
        Object status = res.getAdditionalProperties().get("status");
        return status instanceof Map ? (Map<String, Object>) status : null;
    }

    /** 任意对象 → 子 Map（非 Map → null）。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> mapOf(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    /** 任意对象 → List<Map>（非 List → 空列表，调用方无需判空）。 */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> listOf(Object o) {
        if (!(o instanceof List)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object e : (List<Object>) o) {
            if (e instanceof Map) {
                out.add((Map<String, Object>) e);
            }
        }
        return out;
    }

    static String asStr(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static Integer asInt(Object o) {
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
    static List<String> asStringList(Object o) {
        if (!(o instanceof List)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Object e : (List<Object>) o) {
            if (e != null) {
                out.add(String.valueOf(e));
            }
        }
        return out.isEmpty() ? null : out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> asStringMap(Object o) {
        if (!(o instanceof Map)) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        ((Map<String, Object>) o).forEach((k, v) -> {
            if (v != null) {
                out.put(k, String.valueOf(v));
            }
        });
        return out.isEmpty() ? null : out;
    }

    // ---------- 写（DTO → Map；null/空白不写，交还 CRD 默认） ----------

    static void putStr(Map<String, Object> m, String key, String val) {
        if (val != null && !val.isBlank()) {
            m.put(key, val);
        }
    }

    static void putInt(Map<String, Object> m, String key, Integer val) {
        if (val != null) {
            m.put(key, val);
        }
    }

    static void putNonEmpty(Map<String, Object> m, String key, Object val) {
        if (val == null) {
            return;
        }
        if (val instanceof List<?> l && l.isEmpty()) {
            return;
        }
        if (val instanceof Map<?, ?> mp && mp.isEmpty()) {
            return;
        }
        m.put(key, val);
    }

    static boolean notEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }

    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    // ---------- parentRefs（5 类 Route 共用） ----------

    /** {@code spec.parentRefs} → DTO 列表（非列表 → null）。 */
    static List<ParentRefDTO> parentRefs(Map<String, Object> spec) {
        if (spec == null || !(spec.get("parentRefs") instanceof List)) {
            return null;
        }
        List<ParentRefDTO> out = new ArrayList<>();
        for (Map<String, Object> m : listOf(spec.get("parentRefs"))) {
            ParentRefDTO p = new ParentRefDTO();
            p.setGroup(asStr(m.get("group")));
            p.setKind(asStr(m.get("kind")));
            p.setNamespace(asStr(m.get("namespace")));
            p.setName(asStr(m.get("name")));
            p.setSectionName(asStr(m.get("sectionName")));
            p.setPort(asInt(m.get("port")));
            out.add(p);
        }
        return out.isEmpty() ? null : out;
    }

    /** DTO 列表 → {@code spec.parentRefs}（空 → 空列表，交由调用方决定是否 put）。 */
    static List<Map<String, Object>> toParentRefMaps(List<ParentRefDTO> refs) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (refs == null) {
            return out;
        }
        for (ParentRefDTO p : refs) {
            Map<String, Object> m = new LinkedHashMap<>();
            putStr(m, "group", p.getGroup());
            putStr(m, "kind", p.getKind());
            putStr(m, "namespace", p.getNamespace());
            putStr(m, "name", p.getName());
            putStr(m, "sectionName", p.getSectionName());
            putInt(m, "port", p.getPort());
            out.add(m);
        }
        return out;
    }

    /** overlay 用：命名空间内已有的 parentRef 元素 → 是否为"同一目标"（group/kind/namespace/name 四元组）。 */
    static boolean sameParent(Map<String, Object> live, ParentRefDTO dto) {
        return eq(asStr(live.get("name")), dto.getName())
                && eq(asStr(live.get("namespace")), dto.getNamespace())
                && eq(asStr(live.get("group")), dto.getGroup())
                && eq(asStr(live.get("kind")), dto.getKind());
    }

    // ---------- backendRefs（5 类 Route 共用） ----------

    /** {@code rule.backendRefs} → DTO 列表（非列表 → null）。 */
    static List<BackendRefDTO> backendRefs(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<BackendRefDTO> out = new ArrayList<>();
        for (Map<String, Object> m : listOf(raw)) {
            BackendRefDTO b = new BackendRefDTO();
            b.setGroup(asStr(m.get("group")));
            b.setKind(asStr(m.get("kind")));
            b.setName(asStr(m.get("name")));
            b.setNamespace(asStr(m.get("namespace")));
            b.setPort(asInt(m.get("port")));
            b.setWeight(asInt(m.get("weight")));
            out.add(b);
        }
        return out.isEmpty() ? null : out;
    }

    /** DTO 列表 → backendRefs Map 列表 */
    static List<Map<String, Object>> toBackendRefMaps(List<BackendRefDTO> refs) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (refs == null) {
            return out;
        }
        for (BackendRefDTO b : refs) {
            out.add(toBackendRefMap(b));
        }
        return out;
    }

    /**
     * 单个 backendRef → Map。
     * <p>{@code weight} 是 {@code BackendRef} 独有的字段；RequestMirror 位置用的是 BackendObjectReference
     * （没有 weight），故那里调用本方法后必须剔除 weight —— 见 {@link #toBackendObjectRefMap(BackendRefDTO)}。
     */
    static Map<String, Object> toBackendRefMap(BackendRefDTO b) {
        Map<String, Object> m = new LinkedHashMap<>();
        putStr(m, "group", b.getGroup());
        putStr(m, "kind", b.getKind());
        putStr(m, "name", b.getName());
        putStr(m, "namespace", b.getNamespace());
        putInt(m, "port", b.getPort());
        putInt(m, "weight", b.getWeight());
        return m;
    }

    /** BackendObjectReference 形态（无 weight）：RequestMirror.backendRef 用。 */
    static Map<String, Object> toBackendObjectRefMap(BackendRefDTO b) {
        Map<String, Object> m = toBackendRefMap(b);
        m.remove("weight");
        return m;
    }

    // ---------- status.conditions / status.parents ----------

    /** {@code status.conditions} → DTO 列表（缺席 / 非列表 → null）。 */
    static List<ConditionDTO> conditions(Map<String, Object> status) {
        return status == null ? null : conditionsFromList(status.get("conditions"));
    }

    /** conditions 原始列表 → DTO 列表（Route 的 {@code status.parents[].conditions} 直接传列表，不套一层 status）。 */
    static List<ConditionDTO> conditionsFromList(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<ConditionDTO> out = new ArrayList<>();
        for (Map<String, Object> m : listOf(raw)) {
            out.add(toCondition(m));
        }
        return out.isEmpty() ? null : out;
    }

    /** status.conditions[] 单元素 → DTO（Gateway 的 listenerStatus.conditions 也复用）。 */
    static ConditionDTO toCondition(Map<String, Object> m) {
        ConditionDTO c = new ConditionDTO();
        c.setType(asStr(m.get("type")));
        c.setStatus(asStr(m.get("status")));
        c.setReason(asStr(m.get("reason")));
        c.setMessage(asStr(m.get("message")));
        c.setLastTransitionTime(asStr(m.get("lastTransitionTime")));
        return c;
    }

    // ---------- status.parents（5 类 Route 共用） ----------

    /** {@code status.parents} → DTO 列表（缺席 / 非列表 → null）。HTTP/GRPC/TCP/TLS/UDP 五类 Route 共用。 */
    static List<RouteParentStatusDTO> parentStatuses(Map<String, Object> status) {
        if (status == null || !(status.get("parents") instanceof List)) {
            return null;
        }
        List<RouteParentStatusDTO> out = new ArrayList<>();
        for (Map<String, Object> m : listOf(status.get("parents"))) {
            RouteParentStatusDTO ps = new RouteParentStatusDTO();
            ps.setParentRef(parentRef(mapOf(m.get("parentRef"))));
            ps.setControllerName(asStr(m.get("controllerName")));
            ps.setConditions(conditionsFromList(m.get("conditions")));
            out.add(ps);
        }
        return out.isEmpty() ? null : out;
    }

    /** {@code status.parents[].parentRef} → DTO（Route 状态里的父引用，字段集同 spec.parentRefs）。 */
    static ParentRefDTO parentRef(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        ParentRefDTO p = new ParentRefDTO();
        p.setGroup(asStr(m.get("group")));
        p.setKind(asStr(m.get("kind")));
        p.setNamespace(asStr(m.get("namespace")));
        p.setName(asStr(m.get("name")));
        p.setSectionName(asStr(m.get("sectionName")));
        p.setPort(asInt(m.get("port")));
        return p;
    }

    // ---------- L4 路由（TCP / TLS / UDP）共用：rules = [{name, backendRefs}] ----------

    /**
     * {@code spec.rules} → L4 rule 列表（非列表 → null）。
     * <p>L4 的 rule 没有嵌套 atomic 子列表，也没有未建模子字段（{@code name}/{@code backendRefs} 都建模了），
     * 所以这里不需要按元素 overlay —— 整体替换即无损。
     */
    static List<L4RouteRuleDTO> l4Rules(Map<String, Object> spec) {
        if (spec == null || !(spec.get("rules") instanceof List)) {
            return null;
        }
        List<L4RouteRuleDTO> out = new ArrayList<>();
        for (Map<String, Object> m : listOf(spec.get("rules"))) {
            L4RouteRuleDTO r = new L4RouteRuleDTO();
            r.setName(asStr(m.get("name")));
            r.setBackendRefs(backendRefs(m.get("backendRefs")));
            out.add(r);
        }
        return out.isEmpty() ? null : out;
    }

    /** DTO 列表 → {@code spec.rules} 的 Map 列表 */
    static List<Map<String, Object>> toL4RuleMaps(List<L4RouteRuleDTO> rules) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rules == null) {
            return out;
        }
        for (L4RouteRuleDTO r : rules) {
            Map<String, Object> m = new LinkedHashMap<>();
            putStr(m, "name", r.getName());
            putNonEmpty(m, "backendRefs", toBackendRefMaps(r.getBackendRefs()));
            out.add(m);
        }
        return out;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

}
