package com.coding.k8score.converter.impl.monitoring;

import com.coding.common.models.k8s.dto.PodMonitorDTO;
import com.coding.common.models.k8s.dto.PodMonitorEndpointDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PodMonitorDTO ⇄ GenericKubernetesResource（monitoring.coreos.com/v1 CRD）。
 * 走 fabric8 通用 CRD API，不引代码生成依赖；spec 以 Map 结构读写。
 * <p>CRD 要求 spec.selector 与 spec.podMetricsEndpoints 均必填 → convert 始终输出两者（空也给空结构）。
 * <p>endpoint.port：纯数字（1–65535）→ CRD portNumber(int)；否则 → CRD port(string)。二者只写其一。
 */
public class PodMonitorConverter {

    public static final String API_VERSION = "monitoring.coreos.com/v1";

    public GenericKubernetesResource convert(PodMonitorDTO dto) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion(API_VERSION);
        res.setKind("PodMonitor");
        res.setMetadata(new ObjectMetaBuilder()
                .withName(dto.getName())
                .withNamespace(dto.getNamespace())
                .withLabels(dto.getLabels())
                .build());

        Map<String, Object> spec = new LinkedHashMap<>();

        // selector（必填）：matchLabels 为空也给空 map；matchExpressions 有则带上（功能补全，支持按表达式选 Pod）
        Map<String, Object> selector = new LinkedHashMap<>();
        selector.put("matchLabels", dto.getMatchLabels() != null ? dto.getMatchLabels() : Map.of());
        if (dto.getMatchExpressions() != null && !dto.getMatchExpressions().isEmpty()) {
            selector.put("matchExpressions", toMatchExpressionMaps(dto.getMatchExpressions()));
        }
        spec.put("selector", selector);

        if (dto.getNamespaceSelector() != null) {
            PodMonitorDTO.NamespaceSelector ns = dto.getNamespaceSelector();
            Map<String, Object> nsm = new LinkedHashMap<>();
            putBool(nsm, "any", ns.getAny());
            if (ns.getMatchNames() != null && !ns.getMatchNames().isEmpty()) {
                nsm.put("matchNames", ns.getMatchNames());
            }
            spec.put("namespaceSelector", nsm);
        }

        putStr(spec, "jobLabel", dto.getJobLabel());
        if (dto.getPodTargetLabels() != null && !dto.getPodTargetLabels().isEmpty()) {
            spec.put("podTargetLabels", dto.getPodTargetLabels());
        }
        if (dto.getSampleLimit() != null) {
            spec.put("sampleLimit", dto.getSampleLimit());
        }
        if (dto.getTargetLimit() != null) {
            spec.put("targetLimit", dto.getTargetLimit());
        }
        if (dto.getLabelLimit() != null) {
            spec.put("labelLimit", dto.getLabelLimit());
        }
        putStr(spec, "bodySizeLimit", dto.getBodySizeLimit());
        if (dto.getAttachMetadata() != null && dto.getAttachMetadata().getNode() != null) {
            spec.put("attachMetadata", Map.of("node", dto.getAttachMetadata().getNode()));
        }

        // podMetricsEndpoints（必填）：始终输出，空也给空数组
        spec.put("podMetricsEndpoints", toEndpointMaps(dto.getPodMetricsEndpoints()));

        res.setAdditionalProperty("spec", spec);
        return res;
    }

    @SuppressWarnings("unchecked")
    public PodMonitorDTO revert(GenericKubernetesResource res) {
        PodMonitorDTO dto = new PodMonitorDTO();
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

        Map<String, Object> selector = (Map<String, Object>) spec.get("selector");
        if (selector != null) {
            if (selector.get("matchLabels") instanceof Map) {
                dto.setMatchLabels((Map<String, String>) selector.get("matchLabels"));
            }
            dto.setMatchExpressions(fromMatchExpressionMaps(selector.get("matchExpressions")));
        }

        if (spec.get("namespaceSelector") instanceof Map) {
            Map<String, Object> nsm = (Map<String, Object>) spec.get("namespaceSelector");
            PodMonitorDTO.NamespaceSelector ns = new PodMonitorDTO.NamespaceSelector();
            ns.setAny(asBool(nsm.get("any")));
            ns.setMatchNames(asStringList(nsm.get("matchNames")));
            dto.setNamespaceSelector(ns);
        }

        dto.setJobLabel(asStr(spec.get("jobLabel")));
        dto.setPodTargetLabels(asStringList(spec.get("podTargetLabels")));
        dto.setSampleLimit(asInt(spec.get("sampleLimit")));
        dto.setTargetLimit(asInt(spec.get("targetLimit")));
        dto.setLabelLimit(asInt(spec.get("labelLimit")));
        dto.setBodySizeLimit(asStr(spec.get("bodySizeLimit")));

        if (spec.get("attachMetadata") instanceof Map) {
            PodMonitorDTO.AttachMetadata am = new PodMonitorDTO.AttachMetadata();
            am.setNode(asBool(((Map<String, Object>) spec.get("attachMetadata")).get("node")));
            dto.setAttachMetadata(am);
        }

        List<Map<String, Object>> endpoints = (List<Map<String, Object>>) spec.get("podMetricsEndpoints");
        if (endpoints != null) {
            dto.setPodMetricsEndpoints(endpoints.stream().map(this::fromEndpointMap).toList());
        }
        return dto;
    }

    // ---------- update：fetch-overlay（以线上为底，仅覆盖建模字段，保留 endpoints 未建模的外部字段） ----------

    /** endpoints 的建模字段（overlay 时据此 set/clear；不在此列表的 key 视为外部字段，原样保留——
     *  含 bearerTokenFile/authorization/oauth2/proxy*；tlsConfig 单独深合并，故不列入）。 */
    private static final List<String> MODELED_ENDPOINT_KEYS = List.of(
            "port", "portNumber", "path", "interval", "scrapeTimeout", "scheme",
            "params", "basicAuth", "bearerTokenSecret", "honorLabels",
            "relabelings", "metricRelabelings");

    /**
     * update 专用：CRD 的 spec.podMetricsEndpoints 是 atomic list，SSA/PUT 重声明元素会丢弃未建模子字段
     * （authorization/oauth2/proxyUrl/honorTimestamps/tlsConfig.ca/cert 等）。故以线上对象为底、
     * 按 index 对齐（UI 无排序）逐 endpoint 覆盖建模字段；dto 多出的 endpoint 全新构建，少的视为删除。
     */
    @SuppressWarnings("unchecked")
    public GenericKubernetesResource convertForUpdate(PodMonitorDTO dto, GenericKubernetesResource live) {
        GenericKubernetesResource res = convert(dto);
        List<Map<String, Object>> liveEps = readEndpoints(live);
        List<PodMonitorEndpointDTO> inEps = dto.getPodMetricsEndpoints() != null ? dto.getPodMetricsEndpoints() : List.of();
        List<Map<String, Object>> merged = new ArrayList<>();
        for (int i = 0; i < inEps.size(); i++) {
            Map<String, Object> base = i < liveEps.size() ? liveEps.get(i) : new LinkedHashMap<>();
            merged.add(overlayEndpoint(base, inEps.get(i)));
        }
        Map<String, Object> spec = res.getAdditionalProperties() != null
                ? (Map<String, Object>) res.getAdditionalProperties().get("spec") : null;
        if (spec != null) {
            spec.put("podMetricsEndpoints", merged);
        }
        return res;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readEndpoints(GenericKubernetesResource live) {
        if (live == null || live.getAdditionalProperties() == null) {
            return List.of();
        }
        Object specObj = live.getAdditionalProperties().get("spec");
        if (!(specObj instanceof Map)) {
            return List.of();
        }
        Object eps = ((Map<String, Object>) specObj).get("podMetricsEndpoints");
        if (!(eps instanceof List)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object e : (List<Object>) eps) {
            if (e instanceof Map) {
                out.add((Map<String, Object>) e);
            }
        }
        return out;
    }

    /** 以 base（线上 endpoint）为底，仅覆盖建模字段；未建模 key 保留。tlsConfig 深合并以保 ca/cert/keySecret。 */
    private Map<String, Object> overlayEndpoint(Map<String, Object> base, PodMonitorEndpointDTO dto) {
        Map<String, Object> dtoEp = toEndpointMap(dto); // 仅含非空建模字段
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (String key : MODELED_ENDPOINT_KEYS) {
            if (dtoEp.containsKey(key)) {
                out.put(key, dtoEp.get(key));
            } else {
                out.remove(key); // 用户在表单清空该字段 → 从对象移除
            }
        }
        overlayTlsConfig(out, base, dto);
        return out;
    }

    @SuppressWarnings("unchecked")
    private void overlayTlsConfig(Map<String, Object> out, Map<String, Object> base, PodMonitorEndpointDTO dto) {
        if (dto.getTlsConfig() == null) {
            return; // 未提供 → 保留线上 tlsConfig 原样（含外部 ca/cert/keySecret）
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (base.get("tlsConfig") instanceof Map) {
            merged.putAll((Map<String, Object>) base.get("tlsConfig"));
        }
        PodMonitorEndpointDTO.TlsConfig tc = dto.getTlsConfig();
        if (tc.getInsecureSkipVerify() != null) {
            merged.put("insecureSkipVerify", tc.getInsecureSkipVerify());
        } else {
            merged.remove("insecureSkipVerify");
        }
        if (notBlank(tc.getServerName())) {
            merged.put("serverName", tc.getServerName());
        } else {
            merged.remove("serverName");
        }
        out.put("tlsConfig", merged);
    }

    // ---------- matchExpressions ----------

    private List<Map<String, Object>> toMatchExpressionMaps(List<PodMonitorDTO.MatchExpression> list) {
        return list.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            putStr(m, "key", e.getKey());
            putStr(m, "operator", e.getOperator());
            if (e.getValues() != null && !e.getValues().isEmpty()) {
                m.put("values", e.getValues());
            }
            return m;
        }).toList();
    }

    @SuppressWarnings("unchecked")
    private List<PodMonitorDTO.MatchExpression> fromMatchExpressionMaps(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        return ((List<Map<String, Object>>) raw).stream().map(m -> {
            PodMonitorDTO.MatchExpression e = new PodMonitorDTO.MatchExpression();
            e.setKey(asStr(m.get("key")));
            e.setOperator(asStr(m.get("operator")));
            e.setValues(asStringList(m.get("values")));
            return e;
        }).toList();
    }

    // ---------- endpoint ----------

    private List<Map<String, Object>> toEndpointMaps(List<PodMonitorEndpointDTO> endpoints) {
        if (endpoints == null) {
            return new ArrayList<>();
        }
        return endpoints.stream().map(this::toEndpointMap).toList();
    }

    private Map<String, Object> toEndpointMap(PodMonitorEndpointDTO e) {
        Map<String, Object> m = new LinkedHashMap<>();
        putPort(m, e.getPort());
        putStr(m, "path", e.getPath());
        putStr(m, "interval", e.getInterval());
        putStr(m, "scrapeTimeout", e.getScrapeTimeout());
        putStr(m, "scheme", e.getScheme());
        putBool(m, "honorLabels", e.getHonorLabels());
        if (e.getParams() != null && !e.getParams().isEmpty()) {
            m.put("params", e.getParams());
        }
        if (e.getBasicAuth() != null) {
            Map<String, Object> ba = new LinkedHashMap<>();
            PodMonitorEndpointDTO.SecretRef u = e.getBasicAuth().getUsername();
            if (u != null && notBlank(u.getName())) {
                ba.put("username", toSecretRef(u));
            }
            PodMonitorEndpointDTO.SecretRef p = e.getBasicAuth().getPassword();
            if (p != null && notBlank(p.getName())) {
                ba.put("password", toSecretRef(p));
            }
            if (!ba.isEmpty()) {
                m.put("basicAuth", ba);
            }
        }
        if (e.getBearerTokenSecret() != null && notBlank(e.getBearerTokenSecret().getName())) {
            m.put("bearerTokenSecret", toSecretRef(e.getBearerTokenSecret()));
        }
        if (e.getTlsConfig() != null) {
            Map<String, Object> tls = new LinkedHashMap<>();
            putBool(tls, "insecureSkipVerify", e.getTlsConfig().getInsecureSkipVerify());
            putStr(tls, "serverName", e.getTlsConfig().getServerName());
            if (!tls.isEmpty()) {
                m.put("tlsConfig", tls);
            }
        }
        List<Map<String, Object>> rel = toRelabelingMaps(e.getRelabelings());
        if (!rel.isEmpty()) {
            m.put("relabelings", rel);
        }
        List<Map<String, Object>> mrel = toRelabelingMaps(e.getMetricRelabelings());
        if (!mrel.isEmpty()) {
            m.put("metricRelabelings", mrel);
        }
        return m;
    }

    private PodMonitorEndpointDTO fromEndpointMap(Map<String, Object> e) {
        PodMonitorEndpointDTO ep = new PodMonitorEndpointDTO();
        String named = asStr(e.get("port"));
        if (named != null && !named.isBlank()) {
            ep.setPort(named); // 命名端口优先
        } else {
            Integer num = asInt(e.get("portNumber"));
            if (num != null) {
                ep.setPort(String.valueOf(num));
            }
        }
        ep.setPath(asStr(e.get("path")));
        ep.setInterval(asStr(e.get("interval")));
        ep.setScrapeTimeout(asStr(e.get("scrapeTimeout")));
        ep.setScheme(asStr(e.get("scheme")));
        ep.setHonorLabels(asBool(e.get("honorLabels")));
        if (e.get("params") instanceof Map) {
            Map<String, Object> raw = (Map<String, Object>) e.get("params");
            Map<String, List<String>> params = new LinkedHashMap<>();
            raw.forEach((k, v) -> params.put(k, asStringList(v)));
            ep.setParams(params);
        }
        if (e.get("basicAuth") instanceof Map) {
            Map<String, Object> ba = (Map<String, Object>) e.get("basicAuth");
            PodMonitorEndpointDTO.BasicAuth auth = new PodMonitorEndpointDTO.BasicAuth();
            auth.setUsername(fromSecretRef(ba.get("username")));
            auth.setPassword(fromSecretRef(ba.get("password")));
            ep.setBasicAuth(auth);
        }
        ep.setBearerTokenSecret(fromSecretRef(e.get("bearerTokenSecret")));
        if (e.get("tlsConfig") instanceof Map) {
            Map<String, Object> tls = (Map<String, Object>) e.get("tlsConfig");
            PodMonitorEndpointDTO.TlsConfig tc = new PodMonitorEndpointDTO.TlsConfig();
            tc.setInsecureSkipVerify(asBool(tls.get("insecureSkipVerify")));
            tc.setServerName(asStr(tls.get("serverName")));
            ep.setTlsConfig(tc);
        }
        ep.setRelabelings(fromRelabelingMaps(e.get("relabelings")));
        ep.setMetricRelabelings(fromRelabelingMaps(e.get("metricRelabelings")));
        return ep;
    }

    // ---------- relabeling / secretRef ----------

    private List<Map<String, Object>> toRelabelingMaps(List<PodMonitorEndpointDTO.Relabeling> list) {
        if (list == null) {
            return new ArrayList<>();
        }
        return list.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            if (r.getSourceLabels() != null && !r.getSourceLabels().isEmpty()) {
                m.put("sourceLabels", r.getSourceLabels());
            }
            putStr(m, "targetLabel", r.getTargetLabel());
            putStr(m, "regex", r.getRegex());
            putStr(m, "replacement", r.getReplacement());
            putStr(m, "separator", r.getSeparator());
            if (r.getModulus() != null) {
                m.put("modulus", r.getModulus());
            }
            putStr(m, "action", r.getAction());
            return m;
        }).toList();
    }

    @SuppressWarnings("unchecked")
    private List<PodMonitorEndpointDTO.Relabeling> fromRelabelingMaps(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        return ((List<Map<String, Object>>) raw).stream().map(m -> {
            PodMonitorEndpointDTO.Relabeling r = new PodMonitorEndpointDTO.Relabeling();
            r.setSourceLabels(asStringList(m.get("sourceLabels")));
            r.setTargetLabel(asStr(m.get("targetLabel")));
            r.setRegex(asStr(m.get("regex")));
            r.setReplacement(asStr(m.get("replacement")));
            r.setSeparator(asStr(m.get("separator")));
            Long modulus = asLong(m.get("modulus"));
            r.setModulus(modulus);
            r.setAction(asStr(m.get("action")));
            return r;
        }).toList();
    }

    private Map<String, Object> toSecretRef(PodMonitorEndpointDTO.SecretRef ref) {
        Map<String, Object> m = new LinkedHashMap<>();
        putStr(m, "name", ref.getName());
        putStr(m, "key", ref.getKey());
        return m;
    }

    @SuppressWarnings("unchecked")
    private PodMonitorEndpointDTO.SecretRef fromSecretRef(Object raw) {
        if (!(raw instanceof Map)) {
            return null;
        }
        Map<String, Object> m = (Map<String, Object>) raw;
        PodMonitorEndpointDTO.SecretRef ref = new PodMonitorEndpointDTO.SecretRef();
        ref.setName(asStr(m.get("name")));
        ref.setKey(asStr(m.get("key")));
        return ref;
    }

    // ---------- 小工具：Map 写入 / 读取类型安全转换 ----------

    private static void putStr(Map<String, Object> m, String key, String val) {
        if (notBlank(val)) {
            m.put(key, val);
        }
    }

    /** 纯数字端口（1–65535）→ CRD portNumber(int)；其余（命名端口/越界数字串）→ CRD port(string)。二者只写其一。 */
    private static void putPort(Map<String, Object> m, String port) {
        if (!notBlank(port)) {
            return;
        }
        String p = port.trim();
        if (p.matches("\\d{1,5}")) {
            int n = Integer.parseInt(p);
            if (n >= 1 && n <= 65535) {
                m.put("portNumber", n);
                return;
            }
        }
        m.put("port", p);
    }

    private static void putBool(Map<String, Object> m, String key, Boolean val) {
        if (val != null) {
            m.put(key, val);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
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

    private static Long asLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
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
