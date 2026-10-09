package com.coding.k8score.converter.impl.gateway;

import com.coding.common.models.k8s.dto.BackendRefDTO;
import com.coding.common.models.k8s.dto.ExtensionRefDTO;
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
 * HttpRouteDTO ⇄ GenericKubernetesResource（gateway.networking.k8s.io/v1，命名空间级）。
 * <p>7 类里最复杂的一个 —— 也是<b>唯一必须做 fetch-overlay 才能不丢数据</b>的形态。
 *
 * <h2>为什么必须 overlay（SSA 救不了）</h2>
 * CRD 里 {@code spec.parentRefs}、{@code spec.rules}、{@code rule.matches}、{@code rule.filters}、
 * {@code rule.backendRefs} <b>全是 {@code x-kubernetes-list-type: atomic}</b>
 * （{@code rules} 无 listType 标记，按 CRD 规范默认即 atomic）。atomic list 的 SSA 语义是
 * "整个列表一个值、由 apply 的 fieldManager 独占"：平台 apply 一次不带 {@code rule.timeouts}，
 * 该字段就被整体抹掉，且 apiserver 不会有任何提示。所以：
 * <ul>
 *   <li>{@code rules} —— 按下标以线上元素为底，仅覆盖建模键（{@code name}/{@code matches}/{@code filters}/{@code backendRefs}），
 *       {@code timeouts}/{@code retry}/{@code sessionPersistence} 原样保留。</li>
 *   <li>{@code matches} —— 按 index overlay，元素内只覆盖 {@code path}/{@code method}/{@code headers}/{@code queryParams}。</li>
 *   <li>{@code filters} —— 按 <b>type</b> 对齐（CRD 的 CEL 规定同一 type 至多一个）：
 *       已建模 type 用表单值覆盖；<b>未建模 type（CORS / ExternalAuth）整个元素原样保留</b>，
 *       不因平台编辑而消失。线上元素的相对顺序保持不变，表单新增的 type 追加在末尾。</li>
 *   <li>{@code backendRefs} —— 按 index overlay，元素内覆盖 group/kind/name/namespace/port/weight。</li>
 * </ul>
 * <p>未建模的 spec 顶层键：{@code useDefaultGateways}（v1.6 新增）—— 由顶层 overlay 原样保留。
 * <p>兜底：YAML tab 是只读全保真视图，任何上述未建模内容都能在那里看到。
 */
public class HttpRouteConverter {

    public static final String API_VERSION = "gateway.networking.k8s.io/v1";
    public static final String KIND = "HTTPRoute";

    /** 建模的 spec 顶层键（overlay 时据此 set/clear）。 */
    private static final List<String> MODELED_SPEC_KEYS = List.of("parentRefs", "hostnames", "rules");

    /** 建模的 rule 键（未建模：timeouts / retry / sessionPersistence）。 */
    private static final List<String> MODELED_RULE_KEYS = List.of("name", "matches", "filters", "backendRefs");

    /** 建模的 match 键。 */
    private static final List<String> MODELED_MATCH_KEYS = List.of("path", "method", "headers", "queryParams");

    /** 建模的 header/queryParam match 键。 */
    private static final List<String> MODELED_KEY_VALUE_MATCH_KEYS = List.of("type", "name", "value");

    /** 建模的 filter 键（type 之外）。 */
    private static final List<String> MODELED_FILTER_KEYS = List.of(
            "type", "requestHeaderModifier", "responseHeaderModifier",
            "requestRedirect", "urlRewrite", "requestMirror", "extensionRef");

    /** 已建模的 filter type —— 不在此集合的 type 视为外部 type，整元素保留。 */
    private static final Set<String> MODELED_FILTER_TYPES = Set.of(
            "RequestHeaderModifier", "ResponseHeaderModifier", "RequestRedirect",
            "URLRewrite", "RequestMirror", "ExtensionRef");

    // ==================== 正向：DTO → CRD ====================

    public GenericKubernetesResource convert(HttpRouteDTO dto) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion(API_VERSION);
        res.setKind(KIND);
        res.setMetadata(new ObjectMetaBuilder()
                .withName(dto.getName())
                .withNamespace(dto.getNamespace())
                .withLabels(dto.getLabels())
                .build());

        Map<String, Object> spec = new LinkedHashMap<>();
        if (GatewaySpecUtil.notEmpty(dto.getParentRefs())) {
            spec.put("parentRefs", GatewaySpecUtil.toParentRefMaps(dto.getParentRefs()));
        }
        if (GatewaySpecUtil.notEmpty(dto.getHostnames())) {
            spec.put("hostnames", dto.getHostnames());
        }
        if (GatewaySpecUtil.notEmpty(dto.getRules())) {
            spec.put("rules", toRuleMaps(dto.getRules()));
        }

        res.setAdditionalProperty("spec", spec);
        return res;
    }

    // ==================== 反向：CRD → DTO ====================

    public HttpRouteDTO revert(GenericKubernetesResource res) {
        HttpRouteDTO dto = new HttpRouteDTO();
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
        Map<String, Object> status = GatewaySpecUtil.statusOf(res);
        if (status != null) {
            dto.setParentStatuses(GatewaySpecUtil.parentStatuses(status));
        }
        return dto;
    }

    // ==================== update：fetch-overlay ====================

    /**
     * update 专用。逐层 overlay，语义见类注释：
     * 顶层以线上 spec 为底 set/clear 建模键 → rules 按下标 overlay（新规则全新构建、少的视为删除）
     * → 规则内 matches/backendRefs 按下标、filters 按 type。
     */
    public GenericKubernetesResource convertForUpdate(HttpRouteDTO dto, GenericKubernetesResource live) {
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
                continue; // rules 走逐层 overlay，见下
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

    /** rules 是 atomic list → 整列表替换，故逐元素以线上同下标元素为底做 overlay。 */
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
                        List.of("group", "kind", "name", "namespace", "port", "weight")));
                default -> out.put(key, dto.get(key));
            }
        }
        return out;
    }

    /**
     * 按 type 合并 filters：保持线上顺序 —— 逐个线上元素，已建模 type 用表单同 type 的值替换、
     * 表单里没有该 type 就删除（用户移除了）；<b>未建模 type 原样保留</b>。
     * 最后把线上没有、表单新增的 type 追加到末尾。
     */
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
                out.add(lf); // CORS / ExternalAuth 等外部 type：整元素保留，平台不碰
                continue;
            }
            Map<String, Object> dtoFilter = dtoByType.get(type);
            if (dtoFilter == null) {
                continue; // 建模 type 但表单已移除 → 删除（与 SSA 的"清空即删除"一致）
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

    /** 通用：atomic 子列表按下标 overlay（元素内只覆盖 modeledKeys，其余键保留）。 */
    private List<Map<String, Object>> mergeByIndex(List<Map<String, Object>> live, List<Map<String, Object>> in, List<String> modeledKeys) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            Map<String, Object> base = i < live.size() ? live.get(i) : Map.of();
            out.add(overlayKeys(base, in.get(i), modeledKeys));
        }
        return out;
    }

    /** 以 base 为底，逐个建模键 set/clear，其余键原样保留。 */
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

    private List<Map<String, Object>> toRuleMaps(List<HttpRouteDTO.Rule> rules) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (HttpRouteDTO.Rule r : rules) {
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

    private Map<String, Object> toMatchMap(HttpRouteDTO.Match match) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (match.getPath() != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            GatewaySpecUtil.putStr(p, "type", match.getPath().getType());
            GatewaySpecUtil.putStr(p, "value", match.getPath().getValue());
            if (!p.isEmpty()) {
                m.put("path", p);
            }
        }
        GatewaySpecUtil.putStr(m, "method", match.getMethod());
        if (GatewaySpecUtil.notEmpty(match.getHeaders())) {
            List<Map<String, Object>> headers = new ArrayList<>();
            for (HttpRouteDTO.HeaderMatch h : match.getHeaders()) {
                Map<String, Object> hm = new LinkedHashMap<>();
                GatewaySpecUtil.putStr(hm, "type", h.getType());
                GatewaySpecUtil.putStr(hm, "name", h.getName());
                GatewaySpecUtil.putStr(hm, "value", h.getValue());
                headers.add(hm);
            }
            m.put("headers", headers);
        }
        if (GatewaySpecUtil.notEmpty(match.getQueryParams())) {
            List<Map<String, Object>> params = new ArrayList<>();
            for (HttpRouteDTO.QueryParamMatch q : match.getQueryParams()) {
                Map<String, Object> qm = new LinkedHashMap<>();
                GatewaySpecUtil.putStr(qm, "type", q.getType());
                GatewaySpecUtil.putStr(qm, "name", q.getName());
                GatewaySpecUtil.putStr(qm, "value", q.getValue());
                params.add(qm);
            }
            m.put("queryParams", params);
        }
        return m;
    }

    private Map<String, Object> toFilterMap(HttpRouteDTO.Filter f) {
        Map<String, Object> m = new LinkedHashMap<>();
        GatewaySpecUtil.putStr(m, "type", f.getType());
        GatewaySpecUtil.putNonEmpty(m, "requestHeaderModifier", toHeaderFilterMap(f.getRequestHeaderModifier()));
        GatewaySpecUtil.putNonEmpty(m, "responseHeaderModifier", toHeaderFilterMap(f.getResponseHeaderModifier()));
        GatewaySpecUtil.putNonEmpty(m, "requestRedirect", toRequestRedirectMap(f.getRequestRedirect()));
        GatewaySpecUtil.putNonEmpty(m, "urlRewrite", toUrlRewriteMap(f.getUrlRewrite()));
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

    private Map<String, Object> toRequestRedirectMap(HttpRouteDTO.RequestRedirect r) {
        if (r == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        GatewaySpecUtil.putStr(m, "scheme", r.getScheme());
        GatewaySpecUtil.putStr(m, "hostname", r.getHostname());
        GatewaySpecUtil.putNonEmpty(m, "path", toPathModifierMap(r.getPath()));
        GatewaySpecUtil.putInt(m, "port", r.getPort());
        GatewaySpecUtil.putInt(m, "statusCode", r.getStatusCode());
        return m.isEmpty() ? null : m;
    }

    private Map<String, Object> toUrlRewriteMap(HttpRouteDTO.UrlRewrite r) {
        if (r == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        GatewaySpecUtil.putStr(m, "hostname", r.getHostname());
        GatewaySpecUtil.putNonEmpty(m, "path", toPathModifierMap(r.getPath()));
        return m.isEmpty() ? null : m;
    }

    private Map<String, Object> toPathModifierMap(HttpRouteDTO.PathModifier p) {
        if (p == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        GatewaySpecUtil.putStr(m, "type", p.getType());
        GatewaySpecUtil.putStr(m, "replaceFullPath", p.getReplaceFullPath());
        GatewaySpecUtil.putStr(m, "replacePrefixMatch", p.getReplacePrefixMatch());
        return m.isEmpty() ? null : m;
    }

    private Map<String, Object> toRequestMirrorMap(HttpRouteDTO.RequestMirror r) {
        if (r == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        // 此处是 BackendObjectReference（无 weight）
        GatewaySpecUtil.putNonEmpty(m, "backendRef", r.getBackendRef() == null ? null : GatewaySpecUtil.toBackendObjectRefMap(r.getBackendRef()));
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

    private List<HttpRouteDTO.Rule> fromRuleMaps(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<HttpRouteDTO.Rule> out = new ArrayList<>();
        for (Map<String, Object> m : GatewaySpecUtil.listOf(raw)) {
            HttpRouteDTO.Rule r = new HttpRouteDTO.Rule();
            r.setName(GatewaySpecUtil.asStr(m.get("name")));
            r.setMatches(fromMatchMaps(m.get("matches")));
            r.setFilters(fromFilterMaps(m.get("filters")));
            r.setBackendRefs(GatewaySpecUtil.backendRefs(m.get("backendRefs")));
            out.add(r);
        }
        return out.isEmpty() ? null : out;
    }

    private List<HttpRouteDTO.Match> fromMatchMaps(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<HttpRouteDTO.Match> out = new ArrayList<>();
        for (Map<String, Object> m : GatewaySpecUtil.listOf(raw)) {
            HttpRouteDTO.Match match = new HttpRouteDTO.Match();
            Map<String, Object> p = GatewaySpecUtil.mapOf(m.get("path"));
            if (p != null) {
                HttpRouteDTO.PathMatch pm = new HttpRouteDTO.PathMatch();
                pm.setType(GatewaySpecUtil.asStr(p.get("type")));
                pm.setValue(GatewaySpecUtil.asStr(p.get("value")));
                match.setPath(pm);
            }
            match.setMethod(GatewaySpecUtil.asStr(m.get("method")));
            if (m.get("headers") instanceof List) {
                List<HttpRouteDTO.HeaderMatch> headers = new ArrayList<>();
                for (Map<String, Object> hm : GatewaySpecUtil.listOf(m.get("headers"))) {
                    HttpRouteDTO.HeaderMatch h = new HttpRouteDTO.HeaderMatch();
                    h.setType(GatewaySpecUtil.asStr(hm.get("type")));
                    h.setName(GatewaySpecUtil.asStr(hm.get("name")));
                    h.setValue(GatewaySpecUtil.asStr(hm.get("value")));
                    headers.add(h);
                }
                match.setHeaders(headers.isEmpty() ? null : headers);
            }
            if (m.get("queryParams") instanceof List) {
                List<HttpRouteDTO.QueryParamMatch> params = new ArrayList<>();
                for (Map<String, Object> qm : GatewaySpecUtil.listOf(m.get("queryParams"))) {
                    HttpRouteDTO.QueryParamMatch q = new HttpRouteDTO.QueryParamMatch();
                    q.setType(GatewaySpecUtil.asStr(qm.get("type")));
                    q.setName(GatewaySpecUtil.asStr(qm.get("name")));
                    q.setValue(GatewaySpecUtil.asStr(qm.get("value")));
                    params.add(q);
                }
                match.setQueryParams(params.isEmpty() ? null : params);
            }
            out.add(match);
        }
        return out.isEmpty() ? null : out;
    }

    private List<HttpRouteDTO.Filter> fromFilterMaps(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<HttpRouteDTO.Filter> out = new ArrayList<>();
        for (Map<String, Object> m : GatewaySpecUtil.listOf(raw)) {
            HttpRouteDTO.Filter f = new HttpRouteDTO.Filter();
            f.setType(GatewaySpecUtil.asStr(m.get("type")));
            f.setRequestHeaderModifier(fromHeaderFilterMap(GatewaySpecUtil.mapOf(m.get("requestHeaderModifier"))));
            f.setResponseHeaderModifier(fromHeaderFilterMap(GatewaySpecUtil.mapOf(m.get("responseHeaderModifier"))));
            f.setRequestRedirect(fromRequestRedirectMap(GatewaySpecUtil.mapOf(m.get("requestRedirect"))));
            f.setUrlRewrite(fromUrlRewriteMap(GatewaySpecUtil.mapOf(m.get("urlRewrite"))));
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

    private HttpRouteDTO.RequestRedirect fromRequestRedirectMap(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        HttpRouteDTO.RequestRedirect r = new HttpRouteDTO.RequestRedirect();
        r.setScheme(GatewaySpecUtil.asStr(m.get("scheme")));
        r.setHostname(GatewaySpecUtil.asStr(m.get("hostname")));
        r.setPath(fromPathModifierMap(GatewaySpecUtil.mapOf(m.get("path"))));
        r.setPort(GatewaySpecUtil.asInt(m.get("port")));
        r.setStatusCode(GatewaySpecUtil.asInt(m.get("statusCode")));
        return r;
    }

    private HttpRouteDTO.UrlRewrite fromUrlRewriteMap(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        HttpRouteDTO.UrlRewrite r = new HttpRouteDTO.UrlRewrite();
        r.setHostname(GatewaySpecUtil.asStr(m.get("hostname")));
        r.setPath(fromPathModifierMap(GatewaySpecUtil.mapOf(m.get("path"))));
        return r;
    }

    private HttpRouteDTO.PathModifier fromPathModifierMap(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        HttpRouteDTO.PathModifier p = new HttpRouteDTO.PathModifier();
        p.setType(GatewaySpecUtil.asStr(m.get("type")));
        p.setReplaceFullPath(GatewaySpecUtil.asStr(m.get("replaceFullPath")));
        p.setReplacePrefixMatch(GatewaySpecUtil.asStr(m.get("replacePrefixMatch")));
        return p;
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
