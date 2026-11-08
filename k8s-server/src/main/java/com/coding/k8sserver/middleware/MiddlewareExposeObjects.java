package com.coding.k8sserver.middleware;

import com.coding.common.models.middleware.MiddlewareExposeRequestDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 中间件对外暴露的对象构造（纯函数，便于测试）。 */
final class MiddlewareExposeObjects {

    static final String LABEL = "platform.coding/middleware-expose";

    static final ResourceDefinitionContext HTTP_ROUTE = route("HTTPRoute", "httproutes");
    static final ResourceDefinitionContext TCP_ROUTE = route("TCPRoute", "tcproutes");

    private MiddlewareExposeObjects() {
    }

    private static ResourceDefinitionContext route(String kind, String plural) {
        return new ResourceDefinitionContext.Builder()
                .withGroup("gateway.networking.k8s.io").withVersion("v1")
                .withKind(kind).withPlural(plural).withNamespaced(true).build();
    }

    static String objectName(String instanceName, String endpointKey) {
        String name = instanceName + "-expose-" + endpointKey;
        if (name.length() > 63) {
            throw new IllegalArgumentException("对象名超过 63 个字符：" + name);
        }
        return name;
    }

    static void validate(MiddlewareExposeRequestDTO r) {
        require(r.getClusterId(), "clusterId");
        require(r.getNamespace(), "namespace");
        require(r.getInstanceName(), "instanceName");
        require(r.getEndpointKey(), "endpointKey");
        String mode = r.getMode() == null ? "" : r.getMode();
        switch (mode) {
            case "NONE" -> { }
            case "GATEWAY" -> {
                require(r.getServiceName(), "serviceName");
                require(r.getListenerSetName(), "listenerSetName");
                require(r.getSectionName(), "sectionName");
                if (r.getServicePort() == null) {
                    throw new IllegalArgumentException("servicePort 不能为空");
                }
                if (!"HTTP".equals(r.getProtocol()) && !"TCP".equals(r.getProtocol())) {
                    throw new IllegalArgumentException("protocol 只能是 HTTP / TCP");
                }
            }
            case "NODEPORT" -> {
                require(r.getServiceName(), "serviceName");
                if (r.getServicePort() == null || r.getNodePort() == null) {
                    throw new IllegalArgumentException("servicePort / nodePort 不能为空");
                }
            }
            default -> throw new IllegalArgumentException("mode 只能是 NONE / GATEWAY / NODEPORT");
        }
    }

    private static void require(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }

    /** 受管标签值：{@code ManagedResourceGuard} 按 {@code middleware*} 判成 MIDDLEWARE，路由 / Service 页只读不可删 */
    static final String MANAGED_BY = "middleware-expose";

    /**
     * 实例标签（删实例时按它清理）+ {@code platform.coding/managed-by}：资源页（路由策略 / 服务）据此置灰编辑删除，
     * 后端写入也会被拒 —— 只能在中间件详情「对外访问」里取消。
     */
    static Map<String, String> labels(String instanceName) {
        return Map.of(LABEL, instanceName, "platform.coding/managed-by", MANAGED_BY);
    }

    static GenericKubernetesResource route(MiddlewareExposeRequestDTO r) {
        boolean http = "HTTP".equals(r.getProtocol());
        GenericKubernetesResource res = new GenericKubernetesResource();
        res.setApiVersion("gateway.networking.k8s.io/v1");
        res.setKind(http ? "HTTPRoute" : "TCPRoute");
        res.setMetadata(new ObjectMetaBuilder()
                .withName(objectName(r.getInstanceName(), r.getEndpointKey()))
                .withNamespace(r.getNamespace())
                .withLabels(labels(r.getInstanceName()))
                .build());
        Map<String, Object> parent = new LinkedHashMap<>();
        parent.put("group", "gateway.networking.k8s.io");
        parent.put("kind", "ListenerSet");
        parent.put("name", r.getListenerSetName());
        parent.put("sectionName", r.getSectionName());
        Map<String, Object> backend = new LinkedHashMap<>();
        backend.put("name", r.getServiceName());
        backend.put("port", r.getServicePort());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("parentRefs", List.of(parent));
        spec.put("rules", List.of(Map.of("backendRefs", List.of(backend))));
        res.setAdditionalProperty("spec", spec);
        return res;
    }

    /**
     * NodePort Service：selector 抄目标 Service；端口取目标 Service 上 port == servicePort 的那条，
     * targetPort 照抄（可能是具名端口）。目标 Service 没有这个端口 → 抛错（Operator 还没建好 / 端点表写错）。
     */
    static Service nodePortService(MiddlewareExposeRequestDTO r, Service target) {
        if (target == null || target.getSpec() == null) {
            throw new IllegalStateException("目标 Service " + r.getServiceName() + " 不存在（实例还没就绪？）");
        }
        ServicePort src = target.getSpec().getPorts() == null ? null : target.getSpec().getPorts().stream()
                .filter(p -> r.getServicePort().equals(p.getPort())).findFirst().orElse(null);
        if (src == null) {
            throw new IllegalStateException("目标 Service " + r.getServiceName() + " 没有端口 " + r.getServicePort());
        }
        Map<String, String> selector = target.getSpec().getSelector();
        if (selector == null || selector.isEmpty()) {
            throw new IllegalStateException("目标 Service " + r.getServiceName() + " 没有 selector，无法建 NodePort");
        }
        IntOrString targetPort = src.getTargetPort() == null ? new IntOrString(src.getPort()) : src.getTargetPort();
        return new ServiceBuilder()
                .withNewMetadata()
                .withName(objectName(r.getInstanceName(), r.getEndpointKey()))
                .withNamespace(r.getNamespace())
                .withLabels(labels(r.getInstanceName()))
                .endMetadata()
                .withNewSpec()
                .withType("NodePort")
                .withSelector(new LinkedHashMap<>(selector))
                .addNewPort()
                .withName(r.getEndpointKey())
                .withProtocol("TCP")
                .withPort(r.getServicePort())
                .withTargetPort(targetPort)
                .withNodePort(r.getNodePort())
                .endPort()
                .endSpec()
                .build();
    }
}
