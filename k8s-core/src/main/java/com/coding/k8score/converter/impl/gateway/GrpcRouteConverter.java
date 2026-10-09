package com.coding.k8score.converter.impl.gateway;

import com.coding.common.models.k8s.dto.BackendRefDTO;
import com.coding.common.models.k8s.dto.ExtensionRefDTO;
import com.coding.common.models.k8s.dto.GrpcRouteDTO;
import com.coding.common.models.k8s.dto.HttpRouteDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GrpcRouteDTO ⇄ GenericKubernetesResource（gateway.networking.k8s.io/v1，命名空间级）。
 * <p>与 {@link HttpRouteConverter} 同构的 overlay 策略，但"什么是匹配条件""有哪些 filter"不同：
 * matches 里是 {@code method{type,service,method}} + {@code headers}（<b>无 path、无 queryParams</b>），
 * filters 只有 4 种（<b>无 RequestRedirect / URLRewrite</b>）。
 *
 * <h2>为什么同样必须 fetch-overlay</h2>
 * {@code spec.rules} / {@code rule.matches} / {@code rule.filters} / {@code rule.backendRefs} /
 * {@code spec.parentRefs} 都是 atomic list —— SSA 会把整列表当一个值覆盖，未建模内容（rule 级
 * {@code sessionPersistence}、CRD 升级新增的匹配维度）会被静默抹掉。分层：
 * <ul>
 *   <li>{@code rules} 按下标以线上元素为底，仅覆盖 {@code name}/{@code matches}/{@code filters}/{@code backendRefs}</li>
 *   <li>{@code matches} 按下标 overlay，元素内只覆盖 {@code method}/{@code headers}</li>
 *   <li>{@code filters} 按 <b>type</b> 对齐（CRD 的 CEL 规定同一 type 至多一个）：已建模 type 用表单值覆盖，
 *       <b>未建模 type 整个元素原样保留</b></li>
 *   <li>{@code backendRefs} 按下标 overlay</li>
 * </ul>
 *
 * <p><b>版本</b>：单一 v1（GRPCRoute 自 Gateway API v1.1 GA）。<b>不回退到 v1alpha2</b> ——
 * pre-v1.1 的 v1alpha2 GRPCRoute 结构不同（有 queryParams、filter 类型也不同），
 * 悄悄切过去会写出结构错误的对象；v1 不存在时由 operations 显式报错。
 */
public class GrpcRouteConverter {

    public static final String API_VERSION = "gateway.networking.k8s.io/v1";
    public static final String KIND = "GRPCRoute";

    private static final List<String> MODELED_SPEC_KEYS = List.of("parentRefs", "hostnames", "rules");

    private static final List<String> MODELED_RULE_KEYS = List.of("name", "matches", "filters", "backendRefs");

    private static final List<String> MODELED_MATCH_KEYS = List.of("method", "headers");

    private static final List<String> MODELED_HEADER_KEYS = List.of("type", "name", "value");

    private static final List<String> MODELED_FILTER_KEYS = List.of(
            "type", "requestHeaderModifier", "responseHeaderModifier", "requestMirror", "extensionRef");

    /** 已建模的 filter type —— 不在此集合的 type 视为外部 type，整元素保留。 */
    private static final Set<String> MODELED_FILTER_TYPES = Set.of(
            "RequestHeaderModifier", "ResponseHeaderModifier", "RequestMirror", "ExtensionRef");

    private static final List<String> MODELED_BACKEND_REF_KEYS =
            List.of("group", "kind", "name", "namespace", "port", "weight");

    // ==================== 正向：DTO → CRD ====================

    public GenericKubernetesResource convert(GrpcRouteDTO dto) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion(API_VERSION);
        res.setKind(KIND);
        res.setMetadata(new ObjectMetaBuilder()
                .withName(dto.getName())
                .withNamespace(dto.getNamespace())
                .withLabels(dto.getLabels())
                .build());

        Map<String, Object> spec = new LinkedHashMap<>();
        GatewaySpecUtil.putNonEmpty(spec, "parentRefs", GatewaySpecUtil.toParentRefMaps(dto.getParentRefs()));
        GatewaySpecUtil.putNonEmpty(spec, "hostnames", dto.getHostnames());
        GatewaySpecUtil.putNonEmpty(spec, "rules", toRuleMaps(dto.getRules()));

        res.setAdditionalProperty("spec", spec);
        return res;
    }

    // ==================== 反向：CRD → DTO ====================

    public GrpcRouteDTO revert(GenericKubernetesResource res) {
        GrpcRouteDTO dto = new GrpcRouteDTO();
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
            dto.setRules(fromRuleMaps(spec.get("rules")));
        }
        dto.setParentStatuses(GatewaySpecUtil.parentStatuses(GatewaySpecUtil.statusOf(res)));
        return dto;
    }

    // ==================== update：分层 fetch-overlay ====================

    public GenericKubernetesResource convertForUpdate(GrpcRouteDTO dto, GenericKubernetesResource live) {
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
            if (key.equals("rules")) {
                continue; // rules 逐层 overlay，见下
            }
            if (dtoSpec.containsKey(key)) {
                outSpec.put(key, dtoSpec.get(key));
            } else {
                outSpec.remove(key);
            }
        }

        if (dtoSpec.containsKey("rules")) {
            List<Map<String, Object>> liveRules = GatewaySpecUtil.listOf(liveSpec == null ? null : liveSpec.get("rules"));
            outSpec.put("rules", mergeRules(liveRules, GatewaySpecUtil.listOf(dtoSpec.get("rules"))));
        } else {
            outSpec.remove("rules");
        }

        res.setAdditionalProperty("spec", outSpec);
        return res;
    }

    private List<Map<String, Object>> mergeRules(List<Map<String, Object>> live, List<Map<String, Object>> in) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            Map<String, Object> base = i < live.size() ? live.get(i) : Map.of();
            out.add(overlayRule(base, in.get(i)));
        }
        return out;
    }

    private Map<String, Object> overlayRule(Map<String, Object> base, Map<String, Object> dto) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (String key : MODELED_RULE_KEYS) {
            if (!dto.containsKey(key)) {
                out.remove(key);
            }
        }
        for (String key : MODELED_RULE_KEYS) {
            if (!dto.containsKey(key)) {
                continue;
            }
            switch (key) {
                case "matches" -> out.put("matches", mergeByIndex(
                        GatewaySpecUtil.listOf(base.get("matches")), GatewaySpecUtil.listOf(dto.get("matches")), MODELED_MATCH_KEYS));
                case "filters" -> out.put("filters", mergeFilters(
                        GatewaySpecUtil.listOf(base.get("filters")), GatewaySpecUtil.listOf(dto.get("filters"))));
                case "backendRefs" -> out.put("backendRefs", mergeByIndex(
                        GatewaySpecUtil.listOf(base.get("backendRefs")), GatewaySpecUtil.listOf(dto.get("backendRefs")),
                        MODELED_BACKEND_REF_KEYS));
                default -> out.put(key, dto.get(key));
            }
        }
        return out;
    }

    /** 保持线上顺序：已建模 type 用表单同 type 覆盖/删除，未建模 type 原样保留，新增 type 追加末尾。 */
    private List<Map<String, Object>> mergeFilters(List<Map<String, Object>> live, List<Map<String, Object>> in) {
        Map<String, Map<String, Object>> dtoByType = new LinkedHashMap<>();
        for (Map<String, Object> f : in) {
            dtoByType.put(GatewaySpecUtil.asStr(f.get("type")), f);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> consumed = new LinkedHashSet<>();
        for (Map<String, Object> lf : live) {
            String type = GatewaySpecUtil.asStr(lf.get("type"));
            if (!MODELED_FILTER_TYPES.contains(type)) {
                out.add(lf);
                continue;
            }
            Map<String, Object> dtoFilter = dtoByType.get(type);
            if (dtoFilter == null) {
                continue;
            }
            out.add(overlayKeys(lf, dtoFilter, MODELED_FILTER_KEYS));
            consumed.add(type);
        }
        for (Map.Entry<String, Map<String, Object>> e : dtoByType.entrySet()) {
            if (!consumed.contains(e.getKey())) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    private List<Map<String, Object>> mergeByIndex(List<Map<String, Object>> live, List<Map<String, Object>> in, List<String> modeledKeys) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            Map<String, Object> base = i < live.size() ? live.get(i) : Map.of();
            out.add(overlayKeys(base, in.get(i), modeledKeys));
        }
        return out;
    }

    private Map<String, Object> overlayKeys(Map<String, Object> base, Map<String, Object> dto, List<String> modeledKeys) {
        Map<String, Object> out = new LinkedHashMap<>(base == null ? Map.of() : base);
        for (String key : modeledKeys) {
            if (dto.containsKey(key)) {
                out.put(key, dto.get(key));
            } else {
                out.remove(key);
            }
        }
        return out;
    }

    // ==================== rules 读写 ====================

    private List<Map<String, Object>> toRuleMaps(List<GrpcRouteDTO.Rule> rules) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rules == null) {
            return out;
        }
        for (GrpcRouteDTO.Rule r : rules) {
            Map<String, Object> m = new LinkedHashMap<>();
            GatewaySpecUtil.putStr(m, "name", r.getName());
            if (GatewaySpecUtil.notEmpty(r.getMatches())) {
                m.put("matches", r.getMatches().stream().map(this::toMatchMap).toList());
            }
            if (GatewaySpecUtil.notEmpty(r.getFilters())) {
                m.put("filters", r.getFilters().stream().map(this::toFilterMap).toList());
            }
            if (GatewaySpecUtil.notEmpty(r.getBackendRefs())) {
                m.put("backendRefs", GatewaySpecUtil.toBackendRefMaps(r.getBackendRefs()));
            }
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> toMatchMap(GrpcRouteDTO.Match match) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (match.getMethod() != null) {
            Map<String, Object> mm = new LinkedHashMap<>();
            GatewaySpecUtil.putStr(mm, "type", match.getMethod().getType());
            GatewaySpecUtil.putStr(mm, "service", match.getMethod().getService());
            GatewaySpecUtil.putStr(mm, "method", match.getMethod().getMethod());
            if (!mm.isEmpty()) {
                m.put("method", mm);
            }
        }
        if (GatewaySpecUtil.notEmpty(match.getHeaders())) {
            List<Map<String, Object>> headers = new ArrayList<>();
            for (GrpcRouteDTO.HeaderMatch h : match.getHeaders()) {
                Map<String, Object> hm = new LinkedHashMap<>();
                GatewaySpecUtil.putStr(hm, "type", h.getType());
                GatewaySpecUtil.putStr(hm, "name", h.getName());
                GatewaySpecUtil.putStr(hm, "value", h.getValue());
                headers.add(hm);
            }
            m.put("headers", headers);
        }
        return m;
    }

    private Map<String, Object> toFilterMap(GrpcRouteDTO.Filter f) {
        Map<String, Object> m = new LinkedHashMap<>();
        GatewaySpecUtil.putStr(m, "type", f.getType());
        GatewaySpecUtil.putNonEmpty(m, "requestHeaderModifier", toHeaderFilterMap(f.getRequestHeaderModifier()));
        GatewaySpecUtil.putNonEmpty(m, "responseHeaderModifier", toHeaderFilterMap(f.getResponseHeaderModifier()));
        GatewaySpecUtil.putNonEmpty(m, "requestMirror", toRequestMirrorMap(f.getRequestMirror()));
        GatewaySpecUtil.putNonEmpty(m, "extensionRef", toExtensionRefMap(f.getExtensionRef()));
        return m;
    }

    private Map<String, Object> toHeaderFilterMap(HttpRouteDTO.HeaderFilter hf) {
        if (hf == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        GatewaySpecUtil.putNonEmpty(m, "set", toHeaderValueMaps(hf.getSet()));
        GatewaySpecUtil.putNonEmpty(m, "add", toHeaderValueMaps(hf.getAdd()));
        GatewaySpecUtil.putNonEmpty(m, "remove", hf.getRemove());
        return m.isEmpty() ? null : m;
    }

    private List<Map<String, Object>> toHeaderValueMaps(List<HttpRouteDTO.HeaderValue> values) {
        if (values == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (HttpRouteDTO.HeaderValue v : values) {
            Map<String, Object> m = new LinkedHashMap<>();
            GatewaySpecUtil.putStr(m, "name", v.getName());
            GatewaySpecUtil.putStr(m, "value", v.getValue());
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> toRequestMirrorMap(HttpRouteDTO.RequestMirror r) {
        if (r == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        // 此处是 BackendObjectReference（无 weight）
        GatewaySpecUtil.putNonEmpty(m, "backendRef",
                r.getBackendRef() == null ? null : GatewaySpecUtil.toBackendObjectRefMap(r.getBackendRef()));
        GatewaySpecUtil.putInt(m, "percent", r.getPercent());
        return m.isEmpty() ? null : m;
    }

    private Map<String, Object> toExtensionRefMap(ExtensionRefDTO ref) {
        if (ref == null || !GatewaySpecUtil.notBlank(ref.getName())) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        GatewaySpecUtil.putStr(m, "group", ref.getGroup());
        GatewaySpecUtil.putStr(m, "kind", ref.getKind());
        GatewaySpecUtil.putStr(m, "name", ref.getName());
        return m;
    }

    private List<GrpcRouteDTO.Rule> fromRuleMaps(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<GrpcRouteDTO.Rule> out = new ArrayList<>();
        for (Map<String, Object> m : GatewaySpecUtil.listOf(raw)) {
            GrpcRouteDTO.Rule r = new GrpcRouteDTO.Rule();
            r.setName(GatewaySpecUtil.asStr(m.get("name")));
            r.setMatches(fromMatchMaps(m.get("matches")));
            r.setFilters(fromFilterMaps(m.get("filters")));
            r.setBackendRefs(GatewaySpecUtil.backendRefs(m.get("backendRefs")));
            out.add(r);
        }
        return out.isEmpty() ? null : out;
    }

    private List<GrpcRouteDTO.Match> fromMatchMaps(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<GrpcRouteDTO.Match> out = new ArrayList<>();
        for (Map<String, Object> m : GatewaySpecUtil.listOf(raw)) {
            GrpcRouteDTO.Match match = new GrpcRouteDTO.Match();
            Map<String, Object> mm = GatewaySpecUtil.mapOf(m.get("method"));
            if (mm != null) {
                GrpcRouteDTO.MethodMatch method = new GrpcRouteDTO.MethodMatch();
                method.setType(GatewaySpecUtil.asStr(mm.get("type")));
                method.setService(GatewaySpecUtil.asStr(mm.get("service")));
                method.setMethod(GatewaySpecUtil.asStr(mm.get("method")));
                match.setMethod(method);
            }
            if (m.get("headers") instanceof List) {
                List<GrpcRouteDTO.HeaderMatch> headers = new ArrayList<>();
                for (Map<String, Object> hm : GatewaySpecUtil.listOf(m.get("headers"))) {
                    GrpcRouteDTO.HeaderMatch h = new GrpcRouteDTO.HeaderMatch();
                    h.setType(GatewaySpecUtil.asStr(hm.get("type")));
                    h.setName(GatewaySpecUtil.asStr(hm.get("name")));
                    h.setValue(GatewaySpecUtil.asStr(hm.get("value")));
                    headers.add(h);
                }
                match.setHeaders(headers.isEmpty() ? null : headers);
            }
            out.add(match);
        }
        return out.isEmpty() ? null : out;
    }

    private List<GrpcRouteDTO.Filter> fromFilterMaps(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<GrpcRouteDTO.Filter> out = new ArrayList<>();
        for (Map<String, Object> m : GatewaySpecUtil.listOf(raw)) {
            GrpcRouteDTO.Filter f = new GrpcRouteDTO.Filter();
            f.setType(GatewaySpecUtil.asStr(m.get("type")));
            f.setRequestHeaderModifier(fromHeaderFilterMap(GatewaySpecUtil.mapOf(m.get("requestHeaderModifier"))));
            f.setResponseHeaderModifier(fromHeaderFilterMap(GatewaySpecUtil.mapOf(m.get("responseHeaderModifier"))));
            f.setRequestMirror(fromRequestMirrorMap(GatewaySpecUtil.mapOf(m.get("requestMirror"))));
            f.setExtensionRef(fromExtensionRefMap(GatewaySpecUtil.mapOf(m.get("extensionRef"))));
            out.add(f);
        }
        return out.isEmpty() ? null : out;
    }

    private HttpRouteDTO.HeaderFilter fromHeaderFilterMap(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        HttpRouteDTO.HeaderFilter hf = new HttpRouteDTO.HeaderFilter();
        hf.setSet(fromHeaderValueMaps(m.get("set")));
        hf.setAdd(fromHeaderValueMaps(m.get("add")));
        hf.setRemove(GatewaySpecUtil.asStringList(m.get("remove")));
        return hf;
    }

    private List<HttpRouteDTO.HeaderValue> fromHeaderValueMaps(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<HttpRouteDTO.HeaderValue> out = new ArrayList<>();
        for (Map<String, Object> m : GatewaySpecUtil.listOf(raw)) {
            HttpRouteDTO.HeaderValue v = new HttpRouteDTO.HeaderValue();
            v.setName(GatewaySpecUtil.asStr(m.get("name")));
            v.setValue(GatewaySpecUtil.asStr(m.get("value")));
            out.add(v);
        }
        return out.isEmpty() ? null : out;
    }

    private HttpRouteDTO.RequestMirror fromRequestMirrorMap(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        HttpRouteDTO.RequestMirror r = new HttpRouteDTO.RequestMirror();
        Map<String, Object> b = GatewaySpecUtil.mapOf(m.get("backendRef"));
        if (b != null) {
            BackendRefDTO ref = new BackendRefDTO();
            ref.setGroup(GatewaySpecUtil.asStr(b.get("group")));
            ref.setKind(GatewaySpecUtil.asStr(b.get("kind")));
            ref.setName(GatewaySpecUtil.asStr(b.get("name")));
            ref.setNamespace(GatewaySpecUtil.asStr(b.get("namespace")));
            ref.setPort(GatewaySpecUtil.asInt(b.get("port")));
            r.setBackendRef(ref);
        }
        r.setPercent(GatewaySpecUtil.asInt(m.get("percent")));
        return r;
    }

    private ExtensionRefDTO fromExtensionRefMap(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        ExtensionRefDTO ref = new ExtensionRefDTO();
        ref.setGroup(GatewaySpecUtil.asStr(m.get("group")));
        ref.setKind(GatewaySpecUtil.asStr(m.get("kind")));
        ref.setName(GatewaySpecUtil.asStr(m.get("name")));
        return ref;
    }

}
