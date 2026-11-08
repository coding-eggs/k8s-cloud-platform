package com.coding.k8sserver.components;

import com.coding.common.exception.CloudPlatformException;
import com.coding.common.exception.EnumResponseType;
import com.coding.common.models.k8s.dto.IngressListenerDTO;
import com.coding.common.models.k8s.dto.IngressListenerSetRequestDTO;
import com.coding.common.models.k8s.dto.IngressListenerSetStatusDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 应用端口池的命名空间 ListenerSet（Gateway API v1）。
 *
 * <p>2026-11-08 真集群实测（spec §0）：ListenerSet 挂到入口 Gateway 后，每个 listener 由 Envoy 实际监听；
 * listener 的 {@code allowedRoutes.from: Same} 让别的命名空间的路由挂不上来。
 * 名称 / 端口 / 协议都由 platform-api 按分配表算好，这里只做格式校验与组装。
 */
public final class IngressListenerSetObjects {

    public static final CustomResourceDefinitionContext CRD =
            new CustomResourceDefinitionContext.Builder()
                    .withGroup("gateway.networking.k8s.io")
                    .withVersion("v1")
                    .withKind("ListenerSet")
                    .withPlural("listenersets")
                    .withScope("Namespaced")
                    .build();

    public static final String CRD_NAME = "listenersets.gateway.networking.k8s.io";

    /** 端口方式没有域名，只开 HTTP / TCP */
    private static final Set<String> PROTOCOLS = Set.of("HTTP", "TCP");
    /** Gateway API：一个 ListenerSet 最多 64 个 listener */
    static final int MAX_LISTENERS = 64;

    private IngressListenerSetObjects() {
    }

    public static void validate(IngressListenerSetRequestDTO req) {
        if (req == null || isBlank(req.getNamespace()) || isBlank(req.getName())
                || isBlank(req.getParentName()) || isBlank(req.getParentNamespace())) {
            throw bad("ListenerSet 参数不完整");
        }
        List<IngressListenerDTO> listeners = req.getListeners() == null ? List.of() : req.getListeners();
        if (listeners.size() > MAX_LISTENERS) {
            throw bad("一个 ListenerSet 最多 " + MAX_LISTENERS + " 个 listener，实际 " + listeners.size());
        }
        Set<String> names = new HashSet<>();
        Set<Integer> ports = new HashSet<>();
        for (IngressListenerDTO l : listeners) {
            if (isBlank(l.getName())) {
                throw bad("listener 名称不能为空");
            }
            if (!names.add(l.getName())) {
                throw bad("listener 名称重复：" + l.getName());
            }
            if (l.getPort() == null || l.getPort() < 1 || l.getPort() > 65535) {
                throw bad("listener 端口不合法：" + l.getName() + "=" + l.getPort());
            }
            if (!ports.add(l.getPort())) {
                throw bad("listener 端口重复：" + l.getPort());
            }
            String protocol = l.getProtocol() == null ? "" : l.getProtocol().trim().toUpperCase(Locale.ROOT);
            if (!PROTOCOLS.contains(protocol)) {
                throw bad("端口池 listener 只支持 HTTP / TCP：" + l.getProtocol());
            }
            l.setProtocol(protocol);
        }
    }

    public static GenericKubernetesResource build(IngressListenerSetRequestDTO req) {
        List<Map<String, Object>> specs = new ArrayList<>();
        for (IngressListenerDTO l : req.getListeners()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", l.getName());
            m.put("port", l.getPort());
            m.put("protocol", l.getProtocol().toUpperCase(Locale.ROOT));
            m.put("allowedRoutes", Map.of("namespaces", Map.of("from", "Same")));
            specs.add(m);
        }
        Map<String, Object> parentRef = new LinkedHashMap<>();
        parentRef.put("group", "gateway.networking.k8s.io");
        parentRef.put("kind", "Gateway");
        parentRef.put("name", req.getParentName());
        parentRef.put("namespace", req.getParentNamespace());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("parentRef", parentRef);
        spec.put("listeners", specs);

        GenericKubernetesResource r = new GenericKubernetesResource();
        r.setApiVersion("gateway.networking.k8s.io/v1");
        r.setKind("ListenerSet");
        r.setMetadata(new ObjectMetaBuilder()
                .withName(req.getName())
                .withNamespace(req.getNamespace())
                .addToLabels("platform.coding/managed", "true")
                .build());
        r.setAdditionalProperty("spec", spec);
        return r;
    }

    /** 从现网对象读状态：spec 给端口与协议，status.listeners[].conditions 给是否生效 / 冲突 */
    @SuppressWarnings("unchecked")
    public static IngressListenerSetStatusDTO status(GenericKubernetesResource r) {
        IngressListenerSetStatusDTO out = new IngressListenerSetStatusDTO();
        if (r == null) {
            return out;
        }
        out.setExists(true);
        Map<String, Map<String, Object>> statusByName = new LinkedHashMap<>();
        if (r.getAdditionalProperties().get("status") instanceof Map<?, ?> st
                && st.get("listeners") instanceof List<?> ls) {
            for (Object o : ls) {
                if (o instanceof Map<?, ?> m) {
                    statusByName.put(String.valueOf(m.get("name")), (Map<String, Object>) m);
                }
            }
        }
        if (!(r.getAdditionalProperties().get("spec") instanceof Map<?, ?> spec)
                || !(spec.get("listeners") instanceof List<?> ls)) {
            return out;
        }
        for (Object o : ls) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            IngressListenerSetStatusDTO.Listener l = new IngressListenerSetStatusDTO.Listener();
            l.setName(String.valueOf(m.get("name")));
            l.setPort(m.get("port") instanceof Number n ? n.intValue() : null);
            l.setProtocol(m.get("protocol") == null ? null : String.valueOf(m.get("protocol")));
            Map<String, Object> st = statusByName.get(l.getName());
            if (st != null && st.get("conditions") instanceof List<?> conds) {
                applyConditions(l, conds);
            }
            out.getListeners().add(l);
        }
        return out;
    }

    private static void applyConditions(IngressListenerSetStatusDTO.Listener l, List<?> conds) {
        for (Object c : conds) {
            if (!(c instanceof Map<?, ?> cm)) {
                continue;
            }
            String type = String.valueOf(cm.get("type"));
            boolean isTrue = "True".equals(String.valueOf(cm.get("status")));
            String message = cm.get("message") == null ? null : String.valueOf(cm.get("message"));
            if ("Programmed".equals(type)) {
                l.setProgrammed(isTrue);
            }
            if ("Conflicted".equals(type)) {
                if (isTrue) {
                    // 冲突原因优先于其他条件
                    l.setConflicted(true);
                    l.setReason(String.valueOf(cm.get("reason")));
                    l.setMessage(message);
                }
            } else if (!isTrue && l.getReason() == null) {
                l.setReason(String.valueOf(cm.get("reason")));
                l.setMessage(message);
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static CloudPlatformException bad(String message) {
        return new CloudPlatformException(EnumResponseType.ERROR, message);
    }
}
