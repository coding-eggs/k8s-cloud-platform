package com.coding.k8sserver.components;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;

import java.util.List;
import java.util.Map;

/**
 * Istio tracing 的 {@code Telemetry} CR（批次二）。
 *
 * <p>只对 ingress gateway / waypoint 生效：{@code targetRefs} 指向 Gateway（不是 Deployment），
 * provider 是 istiod 里声明的 {@code platform-otel}。ztunnel 不配置（L4 不出 span）。
 */
public final class OtelTelemetryObjects {

    public static final CustomResourceDefinitionContext CRD =
            new CustomResourceDefinitionContext.Builder()
                    .withGroup("telemetry.istio.io")
                    .withVersion("v1")
                    .withKind("Telemetry")
                    .withPlural("telemetries")
                    .withScope("Namespaced")
                    .build();

    /** 目标 Gateway 的存在性检查（Gateway API 标准资源） */
    public static final CustomResourceDefinitionContext GATEWAY_CRD =
            new CustomResourceDefinitionContext.Builder()
                    .withGroup("gateway.networking.k8s.io")
                    .withVersion("v1")
                    .withKind("Gateway")
                    .withPlural("gateways")
                    .withScope("Namespaced")
                    .build();

    private OtelTelemetryObjects() {
    }

    public static GenericKubernetesResource build(String namespace, String name,
                                                  String gatewayName, int samplingPercentage) {
        Map<String, Object> spec = Map.of(
                "targetRefs", List.of(Map.of(
                        "group", "gateway.networking.k8s.io",
                        "kind", "Gateway",
                        "name", gatewayName)),
                "tracing", List.of(Map.of(
                        "providers", List.of(Map.of("name", "platform-otel")),
                        "randomSamplingPercentage", samplingPercentage)));

        GenericKubernetesResource resource = new GenericKubernetesResource();
        resource.setApiVersion("telemetry.istio.io/v1");
        resource.setKind("Telemetry");
        resource.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(namespace)
                .addToLabels("platform.coding/managed", "true")
                .addToLabels("platform.coding/component", "otel-operator")
                .build());
        resource.setAdditionalProperty("spec", spec);
        return resource;
    }
}
