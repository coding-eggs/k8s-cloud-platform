package com.coding.k8sserver.middleware;

import com.coding.common.models.middleware.MiddlewareCreateRequestDTO;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务指标采集资源的构造：`<名字>-platform-metrics` 的 Service + ServiceMonitor。
 *
 * <p>为什么是 Service + ServiceMonitor 而不是 PodMonitor：本集群 PodMonitor CRD 没有
 * {@code endpoints[].targetPort}，只能引用容器端口名，而各 Operator 的端口命名不归平台管；
 * Service 的端口名由平台定义（固定 {@code metrics}），targetPort 写数字，对所有中间件成立。
 *
 * <p>名字不能是 {@code <名字>-metrics}：Redis Operator 自己就给 exporter 建了同名 Service（实测撞名后
 * 平台打的 label 被它覆盖，ServiceMonitor 什么都选不到）。
 *
 * <p>Nacos（chart）也要用，所以抽成静态工具而不是只放在 {@link MiddlewareAdapter} 的默认方法里。
 */
final class MetricsResources {

    /** core/v1 Service（通用路径创建，与其它资源同一条链）。 */
    static final CustomResourceDefinitionContext SERVICE = new CustomResourceDefinitionContext.Builder()
            .withGroup("").withVersion("v1").withKind("Service").withPlural("services")
            .withScope("Namespaced").build();

    /** Prometheus Operator 的 ServiceMonitor（集群没装时会被跳过，见 MiddlewareInstanceService）。 */
    static final CustomResourceDefinitionContext SERVICE_MONITOR = new CustomResourceDefinitionContext.Builder()
            .withGroup("monitoring.coreos.com").withVersion("v1").withKind("ServiceMonitor")
            .withPlural("servicemonitors").withScope("Namespaced").build();

    static final String NAME_SUFFIX = "-platform-metrics";

    private MetricsResources() {
    }

    static String resourceName(String instanceName) {
        return instanceName + NAME_SUFFIX;
    }

    static List<MiddlewareAdapter.CrSpec> build(MiddlewareCreateRequestDTO req,
                                                MiddlewareAdapter.MetricsSpec spec) {
        String namespace = req.getNamespace();
        String serviceName = resourceName(req.getName());
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("platform.coding/managed-by", "middleware");
        labels.put("platform.coding/middleware-instance",
                req.getInstanceId() == null ? "-" : req.getInstanceId());
        // ServiceMonitor 靠这个标签选中我们的 Service（它的 spec.selector 只按 Service 的 label 选）
        labels.put("platform.coding/middleware-monitor", serviceName);

        GenericKubernetesResource service = new GenericKubernetesResource();
        service.setApiVersion("v1");
        service.setKind("Service");
        service.setMetadata(meta(serviceName, namespace, labels));
        Map<String, Object> serviceSpec = new LinkedHashMap<>();
        serviceSpec.put("type", "ClusterIP");
        serviceSpec.put("selector", parseSelector(spec.podSelector()));
        serviceSpec.put("ports", List.of(Map.of(
                "name", "metrics", "port", spec.port(), "targetPort", spec.port(), "protocol", "TCP")));
        service.setAdditionalProperty("spec", serviceSpec);

        GenericKubernetesResource monitor = new GenericKubernetesResource();
        monitor.setApiVersion("monitoring.coreos.com/v1");
        monitor.setKind("ServiceMonitor");
        monitor.setMetadata(meta(serviceName, namespace, labels));
        Map<String, Object> monitorSpec = new LinkedHashMap<>();
        monitorSpec.put("selector", Map.of("matchLabels",
                Map.of("platform.coding/middleware-monitor", serviceName)));
        monitorSpec.put("namespaceSelector", Map.of("matchNames", List.of(namespace)));
        monitorSpec.put("endpoints", List.of(endpoint(spec)));
        monitor.setAdditionalProperty("spec", monitorSpec);

        return List.of(new MiddlewareAdapter.CrSpec(SERVICE, service),
                new MiddlewareAdapter.CrSpec(SERVICE_MONITOR, monitor));
    }

    /** ServiceMonitor 的 endpoint（分离成静态方法，便于单测断言）。 */
    static Map<String, Object> endpoint(MiddlewareAdapter.MetricsSpec spec) {
        Map<String, Object> endpoint = new LinkedHashMap<>();
        endpoint.put("port", "metrics");
        endpoint.put("path", spec.path());
        endpoint.put("scheme", spec.scheme());
        endpoint.put("interval", "30s");
        return endpoint;
    }

    /** "k=v,k2=v2" → {k=v, k2=v2}；空串得到空 map（没有 selector 时不选任何 Pod）。 */
    static Map<String, String> parseSelector(String selector) {
        Map<String, String> out = new LinkedHashMap<>();
        if (selector == null || selector.isBlank()) {
            return out;
        }
        for (String part : selector.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                out.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return out;
    }

    private static ObjectMeta meta(String name, String namespace, Map<String, String> labels) {
        ObjectMeta meta = new ObjectMeta();
        meta.setName(name);
        meta.setNamespace(namespace);
        meta.setLabels(labels);
        return meta;
    }
}
