package com.coding.k8sserver.components;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;

import java.util.List;
import java.util.Map;

/**
 * collector gateway 的 ServiceMonitor（OTel 批次一）。
 *
 * <p>为什么后置而不是放进 Helm：它依赖 {@code monitoring.coreos.com} 的 CRD；
 * 没装 prometheus-operator 的集群里放进 release 会让整次安装失败，而 traces 并不依赖它。
 * 所以由 platform-api 在 helm 成功后按 CRD 是否存在决定是否下发。
 */
public final class OtelServiceMonitorObjects {

    public static final String NAME = "platform-otel-gateway";

    /** operator 生成的 collector Service 名（{@code <cr-name>-collector}） */
    public static final String SERVICE_NAME = "platform-otel-gateway-collector";

    public static final CustomResourceDefinitionContext CRD =
            new CustomResourceDefinitionContext.Builder()
                    .withGroup("monitoring.coreos.com")
                    .withVersion("v1")
                    .withKind("ServiceMonitor")
                    .withPlural("servicemonitors")
                    .withScope("Namespaced")
                    .build();

    private OtelServiceMonitorObjects() {
    }

    public static GenericKubernetesResource build(String namespace) {
        Map<String, Object> spec = Map.of(
                "selector", Map.of("matchLabels", Map.of(
                        "app.kubernetes.io/name", SERVICE_NAME)),
                "endpoints", List.of(Map.of(
                        "port", "prometheus",
                        "interval", "30s")));

        GenericKubernetesResource resource = new GenericKubernetesResource();
        resource.setApiVersion("monitoring.coreos.com/v1");
        resource.setKind("ServiceMonitor");
        resource.setMetadata(new ObjectMetaBuilder()
                .withName(NAME)
                .withNamespace(namespace)
                .addToLabels("platform.coding/managed", "true")
                .addToLabels("platform.coding/component", "otel-operator")
                .build());
        resource.setAdditionalProperty("spec", spec);
        return resource;
    }
}
