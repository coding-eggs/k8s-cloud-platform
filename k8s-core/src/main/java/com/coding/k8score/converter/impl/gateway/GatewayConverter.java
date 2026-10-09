package com.coding.k8score.converter.impl.gateway;

import com.coding.common.models.k8s.dto.ConditionDTO;
import com.coding.common.models.k8s.dto.GatewayDTO;
import com.coding.common.models.k8s.dto.LabelSelectorDTO;
import com.coding.common.models.k8s.dto.NodeSelectorRequirementDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GatewayDTO ⇄ GenericKubernetesResource（gateway.networking.k8s.io/v1，命名空间级）。
 * <p>走 fabric8 通用 CRD API；spec 以 Map 结构读写。
 *
 * <h2>listeners 为何仍走 fetch-overlay</h2>
 * CRD 里 {@code spec.listeners} 是 <b>{@code listType=map, listMapKey=name}</b>（v1.2 起一直如此），
 * 所以 SSA 本身按 name 逐元素合并、不会整体替换。本类仍显式 overlay（{@link #convertForUpdate}），
 * 目的是把"未建模子字段原样带回"从一条<b>schema 语义推断</b>变成代码里可测的事实：
 * {@code tls.options} / {@code tls.frontendValidation} / {@code allowedRoutes} 的扩展位、
 * 以及 CRD 升级新增的 listener 字段，都会随 apply 原样回写。代价是这些字段的 fieldManager 归属
 * 变成 platform-system —— 与本模块其余资源（ServiceMonitor / IPPool）的口径一致。
 * <p>overlay 按 <b>name</b> 对齐（map list 的键），name 对不上时退化为按下标对齐 —— 后者覆盖
 * "用户在编辑器里改了 listener 名"这一情形，避免改名即丢未建模字段。
 */
public class GatewayConverter {

    public static final String API_VERSION = "gateway.networking.k8s.io/v1";
    public static final String KIND = "Gateway";

    /** 建模的 spec 顶层键（overlay 时据此 set/clear）。 */
    private static final List<String> MODELED_SPEC_KEYS = List.of("gatewayClassName", "listeners", "infrastructure", "addresses");

    /** 建模的 listener 键。 */
    private static final List<String> MODELED_LISTENER_KEYS = List.of("name", "hostname", "port", "protocol", "tls", "allowedRoutes");

    /** 建模的 listener.tls 键（未建模：options / frontendValidation）。 */
    private static final List<String> MODELED_TLS_KEYS = List.of("mode", "certificateRefs");

    /** 建模的 allowedRoutes 键。 */
    private static final List<String> MODELED_ALLOWED_ROUTES_KEYS = List.of("namespaces", "kinds");

    /** 建模的 namespaces 键（selector 内部的 matchExpressions 未建模，由 selector 整体覆盖）。 */
    private static final List<String> MODELED_NAMESPACES_KEYS = List.of("from", "selector");

    // ==================== 正向：DTO → CRD ====================

    public GenericKubernetesResource convert(GatewayDTO dto) {
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion(API_VERSION);
        res.setKind(KIND);
        res.setMetadata(new ObjectMetaBuilder()
                .withName(dto.getName())
                .withNamespace(dto.getNamespace())
                .withLabels(dto.getLabels())
                .build());

        Map<String, Object> spec = new LinkedHashMap<>();
        GatewaySpecUtil.putStr(spec, "gatewayClassName", dto.getGatewayClassName());
        // listeners 必填（CRD MinItems=1）：始终输出，宁可让 apiserver 报出可读的校验错，也不要静默丢字段
        spec.put("listeners", toListenerMaps(dto.getListeners()));
        GatewaySpecUtil.putNonEmpty(spec, "infrastructure", toInfrastructureMap(dto.getInfrastructure()));
        GatewaySpecUtil.putNonEmpty(spec, "addresses", toAddressMaps(dto.getAddresses()));

        res.setAdditionalProperty("spec", spec);
        return res;
    }

    // ==================== 反向：CRD → DTO ====================

    public GatewayDTO revert(GenericKubernetesResource res) {
        GatewayDTO dto = new GatewayDTO();
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
            dto.setGatewayClassName(GatewaySpecUtil.asStr(spec.get("gatewayClassName")));
            dto.setListeners(fromListenerMaps(spec.get("listeners")));
            dto.setInfrastructure(fromInfrastructureMap(GatewaySpecUtil.mapOf(spec.get("infrastructure"))));
            dto.setAddresses(fromAddressMaps(spec.get("addresses")));
        }
        Map<String, Object> status = GatewaySpecUtil.statusOf(res);
        if (status != null) {
            dto.setConditions(GatewaySpecUtil.conditions(status));
            dto.setListenerStatuses(fromListenerStatusMaps(status.get("listeners")));
        }
        return dto;
    }

    // ==================== update：fetch-overlay ====================

    /**
     * update 专用：以线上 spec 为底，覆盖建模字段；{@code listeners} 按 name 逐条 overlay
     * （见类注释），listener 内的 {@code tls} / {@code allowedRoutes} 再做一层对象级 overlay，
     * 保住 {@code tls.options} 等未建模子字段。
     * <p>dto 里多出的 listener = 新建；线上有而 dto 没有 = 删除（map list 语义下 SSA 也会删）。
     */
    public GenericKubernetesResource convertForUpdate(GatewayDTO dto, GenericKubernetesResource live) {
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
        outSpec.put("listeners", mergeListeners(GatewaySpecUtil.listOf(liveSpec == null ? null : liveSpec.get("listeners")),
                GatewaySpecUtil.listOf(dtoSpec.get("listeners"))));
        res.setAdditionalProperty("spec", outSpec);
        return res;
    }

    /** 线上 listeners × 表单 listeners：按 name 对齐（对不上则按下标），逐条对象级 overlay。 */
    private List<Map<String, Object>> mergeListeners(List<Map<String, Object>> live, List<Map<String, Object>> in) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            Map<String, Object> dto = in.get(i);
            Map<String, Object> base = findByListenerName(live, GatewaySpecUtil.asStr(dto.get("name")));
            if (base == null) {
                base = i < live.size() ? live.get(i) : Map.of();
            }
            out.add(overlayListener(base, dto));
        }
        return out;
    }

    private Map<String, Object> findByListenerName(List<Map<String, Object>> live, String name) {
        if (name == null) {
            return null;
        }
        for (Map<String, Object> l : live) {
            if (name.equals(GatewaySpecUtil.asStr(l.get("name")))) {
                return l;
            }
        }
        return null;
    }

    private Map<String, Object> overlayListener(Map<String, Object> base, Map<String, Object> dto) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (String key : MODELED_LISTENER_KEYS) {
            if (!dto.containsKey(key)) {
                out.remove(key);
            }
        }
        for (String key : MODELED_LISTENER_KEYS) {
            if (!dto.containsKey(key)) {
                continue;
            }
            switch (key) {
                // tls / allowedRoutes 是嵌套对象：对象级 overlay，保住未建模子字段
                case "tls" -> putOrRemoveNested(out, "tls", base, dto, MODELED_TLS_KEYS, this::overlayTls);
                case "allowedRoutes" -> putOrRemoveNested(out, "allowedRoutes", base, dto, MODELED_ALLOWED_ROUTES_KEYS, this::overlayAllowedRoutes);
                default -> out.put(key, dto.get(key));
            }
        }
        return out;
    }

    private void putOrRemoveNested(Map<String, Object> out, String key, Map<String, Object> base, Map<String, Object> dto,
                                   List<String> modeledKeys, NestedOverlay overlay) {
        Map<String, Object> dtoChild = GatewaySpecUtil.mapOf(dto.get(key));
        if (dtoChild == null) {
            out.remove(key); // 表单清空整个子对象 → 移除
            return;
        }
        out.put(key, overlay.apply(GatewaySpecUtil.mapOf(base.get(key)), dtoChild, modeledKeys));
    }

    /** listener.tls：仅覆盖 mode / certificateRefs，保留 options / frontendValidation。 */
    private Map<String, Object> overlayTls(Map<String, Object> live, Map<String, Object> dto, List<String> modeledKeys) {
        return overlayKeys(live, dto, modeledKeys);
    }

    /** allowedRoutes：覆盖 namespaces / kinds；namespaces 内再覆盖 from / selector（selector 可含未建模的 matchExpressions）。 */
    private Map<String, Object> overlayAllowedRoutes(Map<String, Object> live, Map<String, Object> dto, List<String> modeledKeys) {
        Map<String, Object> out = overlayKeys(live, dto, modeledKeys);
        Map<String, Object> dtoNs = GatewaySpecUtil.mapOf(dto.get("namespaces"));
        if (dtoNs != null) {
            out.put("namespaces", overlayKeys(GatewaySpecUtil.mapOf(live == null ? null : live.get("namespaces")), dtoNs, MODELED_NAMESPACES_KEYS));
        }
        return out;
    }

    /** 通用对象级 overlay：以 live 为底，逐个建模键 set/clear，其余键原样保留。 */
    private Map<String, Object> overlayKeys(Map<String, Object> live, Map<String, Object> dto, List<String> modeledKeys) {
        Map<String, Object> out = new LinkedHashMap<>(live == null ? Map.of() : live);
        for (String key : modeledKeys) {
            if (dto.containsKey(key)) {
                out.put(key, dto.get(key));
            } else {
                out.remove(key);
            }
        }
        return out;
    }

    @FunctionalInterface
    private interface NestedOverlay {
        Map<String, Object> apply(Map<String, Object> live, Map<String, Object> dto, List<String> modeledKeys);
    }

    // ==================== listeners 读写 ====================

    private List<Map<String, Object>> toListenerMaps(List<GatewayDTO.Listener> listeners) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (listeners == null) {
            return out;
        }
        for (GatewayDTO.Listener l : listeners) {
            Map<String, Object> m = new LinkedHashMap<>();
            GatewaySpecUtil.putStr(m, "name", l.getName());
            GatewaySpecUtil.putStr(m, "hostname", l.getHostname());
            GatewaySpecUtil.putInt(m, "port", l.getPort());
            GatewaySpecUtil.putStr(m, "protocol", l.getProtocol());
            GatewaySpecUtil.putNonEmpty(m, "tls", toTlsMap(l.getTls()));
            GatewaySpecUtil.putNonEmpty(m, "allowedRoutes", toAllowedRoutesMap(l.getAllowedRoutes()));
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> toTlsMap(GatewayDTO.ListenerTls tls) {
        if (tls == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        GatewaySpecUtil.putStr(m, "mode", tls.getMode());
        if (GatewaySpecUtil.notEmpty(tls.getCertificateRefs())) {
            List<Map<String, Object>> refs = new ArrayList<>();
            for (GatewayDTO.CertificateRef r : tls.getCertificateRefs()) {
                Map<String, Object> rm = new LinkedHashMap<>();
                GatewaySpecUtil.putStr(rm, "group", r.getGroup());
                GatewaySpecUtil.putStr(rm, "kind", r.getKind());
                GatewaySpecUtil.putStr(rm, "name", r.getName());
                GatewaySpecUtil.putStr(rm, "namespace", r.getNamespace());
                refs.add(rm);
            }
            m.put("certificateRefs", refs);
        }
        return m.isEmpty() ? null : m;
    }

    private Map<String, Object> toAllowedRoutesMap(GatewayDTO.AllowedRoutes ar) {
        if (ar == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        GatewayDTO.RouteNamespaces ns = ar.getNamespaces();
        if (ns != null) {
            Map<String, Object> nsm = new LinkedHashMap<>();
            GatewaySpecUtil.putStr(nsm, "from", ns.getFrom());
            GatewaySpecUtil.putNonEmpty(nsm, "selector", toSelectorMap(ns.getSelector()));
            if (!nsm.isEmpty()) {
                m.put("namespaces", nsm);
            }
        }
        if (GatewaySpecUtil.notEmpty(ar.getKinds())) {
            List<Map<String, Object>> kinds = new ArrayList<>();
            for (GatewayDTO.RouteGroupKind k : ar.getKinds()) {
                Map<String, Object> km = new LinkedHashMap<>();
                GatewaySpecUtil.putStr(km, "group", k.getGroup());
                GatewaySpecUtil.putStr(km, "kind", k.getKind());
                kinds.add(km);
            }
            m.put("kinds", kinds);
        }
        return m.isEmpty() ? null : m;
    }

    private Map<String, Object> toSelectorMap(LabelSelectorDTO sel) {
        if (sel == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        GatewaySpecUtil.putNonEmpty(m, "matchLabels", sel.getMatchLabels());
        if (GatewaySpecUtil.notEmpty(sel.getMatchExpressions())) {
            List<Map<String, Object>> exprs = new ArrayList<>();
            for (var e : sel.getMatchExpressions()) {
                Map<String, Object> em = new LinkedHashMap<>();
                GatewaySpecUtil.putStr(em, "key", e.getKey());
                GatewaySpecUtil.putStr(em, "operator", e.getOperator());
                GatewaySpecUtil.putNonEmpty(em, "values", e.getValues());
                exprs.add(em);
            }
            m.put("matchExpressions", exprs);
        }
        return m.isEmpty() ? null : m;
    }

    private Map<String, Object> toInfrastructureMap(GatewayDTO.Infrastructure infra) {
        if (infra == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        GatewaySpecUtil.putNonEmpty(m, "labels", infra.getLabels());
        GatewaySpecUtil.putNonEmpty(m, "annotations", infra.getAnnotations());
        // parametersRef 未建模：不写。update 时由 overlay 从线上保留
        return m.isEmpty() ? null : m;
    }

    private List<Map<String, Object>> toAddressMaps(List<GatewayDTO.Address> addresses) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (addresses == null) {
            return out;
        }
        for (GatewayDTO.Address a : addresses) {
            Map<String, Object> m = new LinkedHashMap<>();
            GatewaySpecUtil.putStr(m, "type", a.getType());
            GatewaySpecUtil.putStr(m, "value", a.getValue());
            out.add(m);
        }
        return out;
    }

    private List<GatewayDTO.Listener> fromListenerMaps(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<GatewayDTO.Listener> out = new ArrayList<>();
        for (Map<String, Object> m : GatewaySpecUtil.listOf(raw)) {
            GatewayDTO.Listener l = new GatewayDTO.Listener();
            l.setName(GatewaySpecUtil.asStr(m.get("name")));
            l.setHostname(GatewaySpecUtil.asStr(m.get("hostname")));
            l.setPort(GatewaySpecUtil.asInt(m.get("port")));
            l.setProtocol(GatewaySpecUtil.asStr(m.get("protocol")));
            l.setTls(fromTlsMap(GatewaySpecUtil.mapOf(m.get("tls"))));
            l.setAllowedRoutes(fromAllowedRoutesMap(GatewaySpecUtil.mapOf(m.get("allowedRoutes"))));
            out.add(l);
        }
        return out.isEmpty() ? null : out;
    }

    private GatewayDTO.ListenerTls fromTlsMap(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        GatewayDTO.ListenerTls tls = new GatewayDTO.ListenerTls();
        tls.setMode(GatewaySpecUtil.asStr(m.get("mode")));
        if (m.get("certificateRefs") instanceof List) {
            List<GatewayDTO.CertificateRef> refs = new ArrayList<>();
            for (Map<String, Object> rm : GatewaySpecUtil.listOf(m.get("certificateRefs"))) {
                GatewayDTO.CertificateRef r = new GatewayDTO.CertificateRef();
                r.setGroup(GatewaySpecUtil.asStr(rm.get("group")));
                r.setKind(GatewaySpecUtil.asStr(rm.get("kind")));
                r.setName(GatewaySpecUtil.asStr(rm.get("name")));
                r.setNamespace(GatewaySpecUtil.asStr(rm.get("namespace")));
                refs.add(r);
            }
            tls.setCertificateRefs(refs.isEmpty() ? null : refs);
        }
        return tls;
    }

    private GatewayDTO.AllowedRoutes fromAllowedRoutesMap(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        GatewayDTO.AllowedRoutes ar = new GatewayDTO.AllowedRoutes();
        Map<String, Object> nsm = GatewaySpecUtil.mapOf(m.get("namespaces"));
        if (nsm != null) {
            GatewayDTO.RouteNamespaces ns = new GatewayDTO.RouteNamespaces();
            ns.setFrom(GatewaySpecUtil.asStr(nsm.get("from")));
            ns.setSelector(fromSelectorMap(GatewaySpecUtil.mapOf(nsm.get("selector"))));
            ar.setNamespaces(ns);
        }
        if (m.get("kinds") instanceof List) {
            List<GatewayDTO.RouteGroupKind> kinds = new ArrayList<>();
            for (Map<String, Object> km : GatewaySpecUtil.listOf(m.get("kinds"))) {
                GatewayDTO.RouteGroupKind k = new GatewayDTO.RouteGroupKind();
                k.setGroup(GatewaySpecUtil.asStr(km.get("group")));
                k.setKind(GatewaySpecUtil.asStr(km.get("kind")));
                kinds.add(k);
            }
            ar.setKinds(kinds.isEmpty() ? null : kinds);
        }
        return ar;
    }

    private LabelSelectorDTO fromSelectorMap(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        LabelSelectorDTO sel = new LabelSelectorDTO();
        sel.setMatchLabels(GatewaySpecUtil.asStringMap(m.get("matchLabels")));
        if (m.get("matchExpressions") instanceof List) {
            List<NodeSelectorRequirementDTO> exprs = new ArrayList<>();
            for (Map<String, Object> em : GatewaySpecUtil.listOf(m.get("matchExpressions"))) {
                NodeSelectorRequirementDTO e = new NodeSelectorRequirementDTO();
                e.setKey(GatewaySpecUtil.asStr(em.get("key")));
                e.setOperator(GatewaySpecUtil.asStr(em.get("operator")));
                e.setValues(GatewaySpecUtil.asStringList(em.get("values")));
                exprs.add(e);
            }
            sel.setMatchExpressions(exprs.isEmpty() ? null : exprs);
        }
        return sel;
    }

    private GatewayDTO.Infrastructure fromInfrastructureMap(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        GatewayDTO.Infrastructure infra = new GatewayDTO.Infrastructure();
        infra.setLabels(GatewaySpecUtil.asStringMap(m.get("labels")));
        infra.setAnnotations(GatewaySpecUtil.asStringMap(m.get("annotations")));
        return infra;
    }

    private List<GatewayDTO.Address> fromAddressMaps(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<GatewayDTO.Address> out = new ArrayList<>();
        for (Map<String, Object> m : GatewaySpecUtil.listOf(raw)) {
            GatewayDTO.Address a = new GatewayDTO.Address();
            a.setType(GatewaySpecUtil.asStr(m.get("type")));
            a.setValue(GatewaySpecUtil.asStr(m.get("value")));
            out.add(a);
        }
        return out.isEmpty() ? null : out;
    }

    private List<GatewayDTO.ListenerStatus> fromListenerStatusMaps(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<GatewayDTO.ListenerStatus> out = new ArrayList<>();
        for (Map<String, Object> m : GatewaySpecUtil.listOf(raw)) {
            GatewayDTO.ListenerStatus ls = new GatewayDTO.ListenerStatus();
            ls.setName(GatewaySpecUtil.asStr(m.get("name")));
            ls.setAttachedRoutes(GatewaySpecUtil.asInt(m.get("attachedRoutes")));
            if (m.get("supportedKinds") instanceof List) {
                List<GatewayDTO.RouteGroupKind> kinds = new ArrayList<>();
                for (Map<String, Object> km : GatewaySpecUtil.listOf(m.get("supportedKinds"))) {
                    GatewayDTO.RouteGroupKind k = new GatewayDTO.RouteGroupKind();
                    k.setGroup(GatewaySpecUtil.asStr(km.get("group")));
                    k.setKind(GatewaySpecUtil.asStr(km.get("kind")));
                    kinds.add(k);
                }
                ls.setSupportedKinds(kinds.isEmpty() ? null : kinds);
            }
            if (m.get("conditions") instanceof List) {
                List<ConditionDTO> conds = new ArrayList<>();
                for (Map<String, Object> cm : GatewaySpecUtil.listOf(m.get("conditions"))) {
                    conds.add(GatewaySpecUtil.toCondition(cm));
                }
                ls.setConditions(conds.isEmpty() ? null : conds);
            }
            out.add(ls);
        }
        return out.isEmpty() ? null : out;
    }

}
