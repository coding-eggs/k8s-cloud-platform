package com.coding.k8sserver.components;

import com.coding.common.models.k8s.dto.GatewayListenerDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对外入口的 Gateway CR（平台安装 Istio 时下发）。
 *
 * <p>背景（2026-10-20 实锤）：{@code istio-gateway} chart 只建 Deployment + Service，不建 Gateway CR。
 * 自建网关没有 {@code <网关名>-istio} 这个默认 Service，Istio 的 status 控制器就找不到承载它的 Service，
 * {@code Programmed=False / AddressNotUsable}。所以由组件负责建 CR 并写死绑定：
 * {@code spec.addresses = <service>.<namespace>.svc.cluster.local}。
 *
 * <p>listener 由安装表单的端口表派生（端口 = targetPort，即 Envoy 实际监听端口）：
 * HTTP 挂 HTTPRoute；HTTPS = Terminate + 证书 Secret；TLS = Passthrough；TCP/UDP 直通。
 */
public final class ExternalGatewayObjects {

    public static final CustomResourceDefinitionContext CRD =
            new CustomResourceDefinitionContext.Builder()
                    .withGroup("gateway.networking.k8s.io")
                    .withVersion("v1")
                    .withKind("Gateway")
                    .withPlural("gateways")
                    .withScope("Namespaced")
                    .build();

    public static final String CRD_NAME = "gateways.gateway.networking.k8s.io";

    /** Gateway API listener 支持的协议（Istio 实现） */
    public static final Set<String> PROTOCOLS = Set.of("HTTP", "HTTPS", "TLS", "TCP", "UDP");

    private ExternalGatewayObjects() {
    }

    public static GenericKubernetesResource build(String namespace, String name, String serviceName,
                                                  List<GatewayListenerDTO> listeners) {
        return buildWithSpecs(namespace, name, serviceName, listenerSpecs(listeners));
    }

    /** 把 listener DTO 转成 Gateway CR 的 listener 结构（纯函数，单测直接断言） */
    public static List<Map<String, Object>> listenerSpecs(List<GatewayListenerDTO> listeners) {
        List<Map<String, Object>> specs = new ArrayList<>();
        for (GatewayListenerDTO l : listeners) {
            Map<String, Object> m = listenerSpec(l);
            specs.add(m);
        }
        return specs;
    }

    /**
     * 按 listener 名合并：incoming 覆盖同名项，其余保留（顺序：先已有的，再新增的）。
     *
     * <p>组件们（Harbor 的 443、以后 S3 的 443）各自只带自己那一条，靠这个函数并进 Istio 的端口表。
     */
    public static List<Map<String, Object>> mergeByName(List<Map<String, Object>> existing,
                                                        List<Map<String, Object>> incoming) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        if (existing != null) {
            for (Map<String, Object> l : existing) {
                merged.put(String.valueOf(l.get("name")), l);
            }
        }
        if (incoming != null) {
            for (Map<String, Object> l : incoming) {
                merged.put(String.valueOf(l.get("name")), l);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private static Map<String, Object> listenerSpec(GatewayListenerDTO l) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", l.getName());
            m.put("port", l.getPort());
            m.put("protocol", l.getProtocol());
            m.put("allowedRoutes", Map.of("namespaces", Map.of("from", "All")));
            if (l.getHostname() != null && !l.getHostname().isBlank()) {
                m.put("hostname", l.getHostname().trim());
            }
            if ("HTTPS".equals(l.getProtocol()) || "TLS".equals(l.getProtocol())) {
                String mode = l.getTlsMode();
                if (mode == null || mode.isBlank()) {
                    // 未显式指定：HTTPS 默认 Terminate（要有证书），TLS 默认 Passthrough
                    mode = "HTTPS".equals(l.getProtocol()) ? "Terminate" : "Passthrough";
                }
                if ("Passthrough".equals(mode)) {
                    m.put("tls", Map.of("mode", "Passthrough"));
                } else {
                    m.put("tls", Map.of(
                            "mode", "Terminate",
                            "certificateRefs", List.of(Map.of(
                                    "group", "",
                                    "kind", "Secret",
                                    "name", l.getTlsSecret()))));
                }
            }
        return m;
    }

    /** 低层构建：listener 已经是要写进 CR 的结构（合并路径用） */
    public static GenericKubernetesResource buildWithSpecs(String namespace, String name,
                                                           String serviceName,
                                                           List<Map<String, Object>> specs) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("gatewayClassName", "istio");
        spec.put("addresses", List.of(Map.of(
                "type", "Hostname",
                "value", serviceName + "." + namespace + ".svc.cluster.local")));
        spec.put("listeners", specs);

        GenericKubernetesResource resource = new GenericKubernetesResource();
        resource.setApiVersion("gateway.networking.k8s.io/v1");
        resource.setKind("Gateway");
        resource.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(namespace)
                .addToLabels("platform.coding/managed", "true")
                .addToLabels("platform.coding/component", "istio")
                .build());
        resource.setAdditionalProperty("spec", spec);
        return resource;
    }
}
